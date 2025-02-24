def call(Map buildParams = [:]) {
    // Platforms
    def platformsValid = [
        'Android_arm': 'arm-linux-androideabi',
        'Android_arm64': 'aarch64-linux-android',
        'Android_x86': 'i686-linux-android',
        'Linux_arm': 'arm-linux-gnueabi',
        'Linux_arm64': 'aarch64-linux-gnu',
        'Linux_x86_64': 'x86_64-linux-gnu',
        'Ubuntu-ppa': 'linux-native',
        'Linux_webos': 'arm-webos-linux-gnueabi'
    ]

    // make sure Jenkins job name starts with the platform name, e.g. Android, Linux
    def defaultPlatformChoices = platformsValid.keySet().findAll{ p -> p.toLowerCase() =~ env.JOB_BASE_NAME.toLowerCase().split('-')[0] }.asList()
    def platformChoices = buildParams.containsKey('platform') && platformsValid.containsKey(buildParams.platform) ? buildParams.platform : defaultPlatformChoices
    def platform = buildParams.containsKey('platform') && platformsValid.containsKey(buildParams.platform) ? buildParams.platform : params.PLATFORM

    // Linux
    def renderSystemsValid = ['gles', 'gl']
    def renderSystemChoices = platform =~ /Linux/ ? renderSystemsValid : 'ignored'
    def toolChain = buildParams.containsKey('toolchain_path') ? buildParams.toolchain_path : '/usr'

    // Android
    def ndksValid = ['27.2.12479018', '26.2.11394342', '21.4.7075529']
    def ndkChoices = platform =~ /Android/ ? ndksValid : 'ignored'
    def ndkVer = env.NDK_VERSION ?: (buildParams.containsKey('ndk_version') ? buildParams.ndk_version : params.NDK)
    def sdkPath = buildParams.containsKey('sdk_path') ? buildParams.sdk_path : '/home/jenkins/android-tools/android-sdk-linux'
    def ndkPath = buildParams.containsKey('ndk_path') ? buildParams.ndk_path : "${sdkPath}/ndk"

    // Docker vars
    def defaultImage = platform =~ /Linux/ ? 'kodi/jenkins/linux-arm:latest' : 'kodi/jenkins/android-build:latest'
    def buildImage = buildParams.containsKey('image') ? buildParams.image : defaultImage
    def renderSystem = buildParams.containsKey('rendersystem') && renderSystemsValid.contains(buildParams.rendersystem) ? buildParams.rendersystem : params.RENDERSYSTEM
    def extraMount = platform =~ /Android/ ? '-v /home/jenkins/android-tools/signing:/home/jenkins/android-tools/signing:ro -v /home/jenkins/jenkins-root/workspace/.gradle:/home/jenkins/.gradle:rw' : ''
    def ccacheDir = '.ccache' + platform

    def uploadArtifact = (env.UPLOAD_RESULT == true || env.UPLOAD_RESULT == 'true' || env.UPLOAD_RESULT == 'True') ? true : params.UPLOAD_RESULT
    def uploadFolder = env.BUILD_CAUSE == 'TIMERTRIGGER' || env.UPSTREAM_BUILD_CAUSE == 'TIMERTRIGGER' ? 'nightlies' : 'test-builds'

    def defaultAddons = buildParams.containsKey('binary_addons') ? buildParams.binary_addons : 'peripheral.joystick'

    // Build Job vars
    env.BUILDTHREADS = buildParams.containsKey('buildthreads') && buildParams.buildthreads <= 64 ? buildParams.buildthreads : 64
    env.WORKSPACESUFFIX = platform =~ /Linux/ ? platform.toLowerCase() + renderSystem : ''
    env.TARBALLS_DIR = '$WORKSPACE/../xbmc-tarballs'
    env.CONFIGURATION = params.Configuration == 'Release' ? 'Release' : 'Debug'
    env.BUILD_CAUSE = env.BUILD_CAUSE ?: 'manual'
    env.UPSTREAM_BUILD_CAUSE = env.UPSTREAM_BUILD_CAUSE ?: 'none'
    env.ADDONS = buildParams.containsKey('addons') ? buildParams.addons : params.ADDONS
    env.BUILD_BINARY_ADDONS = params.BUILD_BINARY_ADDONS
    env.RUN_TESTS = buildParams.containsKey('RUN_TESTS') ? buildParams.RUN_TESTS : params.RUN_TESTS
    def qualityGateThreshold = buildParams.containsKey('qualityGateThreshold') ? buildParams.qualityGateThreshold : 1

    // Testing execution
    def testablePlatforms = [
        'Linux_x86_64'
    ]
    boolean allowTests = testablePlatforms.contains(platform) ? true : false
    env.FILTER_TESTS = buildParams.containsKey('FILTER_TESTS') ? '--gtest_filter=' + buildParams.FILTER_TESTS : ''

    // Globals
    def verifyHash = ''
    def os = ''
    def uploadFileName = ''
    def publishPattern = ''
    def canUpload = true
    def uploadSubDir = ''
    env.BUILD_HOST = ''
    env.PULLID = ''
    env.PULLREFSPEC = ''
    env.CONFIGEXTRA = ''
    env.filename = ''
    env.uploadFile = ''
    env.CLITOOL = ''


    pipeline {
        options {
            skipDefaultCheckout true
            buildDiscarder(logRotator(daysToKeepStr: '7', numToKeepStr: '35', artifactNumToKeepStr: '20'))
        }

        parameters {
            choice(name: 'Configuration', choices: ['Default', 'Debug', 'Release'], description: 'build type')
            string(name: 'Revision', defaultValue: 'master', description: 'master')
            string(name: 'GITHUB_REPO', defaultValue: 'xbmc', description: 'The repository/fork to build from. (e.x. the name of the github user).')
            choice(name: 'PLATFORM', choices: platformChoices, description: 'Platform to build for')
            choice(name: 'RENDERSYSTEM', choices: renderSystemChoices, description: 'Linux only: Render system to build for')
            choice(name: 'NDK', choices: ndkChoices, description: 'Android only: android NDK')
            booleanParam(name: 'BUILD_BINARY_ADDONS', defaultValue: true, description: 'Whether binary addons should be built during or not.')
            string(name: 'ADDONS', defaultValue: defaultAddons, description: 'Which binary addons should be built.')
            booleanParam(name: 'RUN_TESTS', defaultValue: false, description: 'Turn this on if you want to build and run the xbmc unit tests based on  gtest.')
            booleanParam(name: 'UPLOAD_RESULT', defaultValue: false, description: 'Whether the resulting builds should be uploaded to test-builds')
            string(name: 'PR', defaultValue: null, description: 'Pull Request to build. Overrides Revision, empty for normal build')
        }

        agent {
            docker {
                image buildImage
                label 'docker-android || docker-linux'
                args '--userns=keep-id ' +
                    '-v /home/jenkins/jenkins-root/workspace/' + ccacheDir + ':/home/jenkins/.ccache:rw ' +
                    '-v /home/jenkins/jenkins-root/workspace/xbmc-tarballs:/home/jenkins/jenkins-root/workspace/xbmc-tarballs:rw ' + extraMount
                customWorkspace "workspace/${JOB_BASE_NAME}${WORKSPACESUFFIX}"
            }
        }

        stages {
            stage('Checkout Scm') {
                steps {
                    script {
                        env.GITHUB_REPO = params.GITHUB_REPO.trim()

                        if (env.ghprbPullId && env.ghprbPullId != 'null') {
                            env.PULLID = env.ghprbPullId.trim()
                            echo "setting PULLID to ghprbPullId: ${PULLID}"
                        }
                        else if (params.PR) {
                            env.PULLID = params.PR.trim()
                            echo "setting PULLID to params.PR: ${PULLID}"
                        }
                        if (env.PULLID) {
                            env.Revision = "refs/remotes/${GITHUB_REPO}/pr/${PULLID}/merge"
                            echo "Building Pull Request: ${PULLID}, overriding Revision: ${Revision}"
                            env.PULLREFSPEC = "+refs/pull/${PULLID}/*:refs/remotes/origin/pr/${PULLID}/* +refs/pull/${PULLID}/*:refs/remotes/${GITHUB_REPO}/pr/${PULLID}/*"
                        }
                        echo "Building host: ${BUILD_HOST}  config: ${CONFIGURATION} revision: ${Revision} repo: ${GITHUB_REPO}"
                    }
                    checkout(
                        [$class: 'GitSCM', branches: [[name: "${Revision}"]],
                        browser: [$class: 'GithubWeb', repoUrl: "https://github.com/${GITHUB_REPO}/xbmc/"],
                            extensions: [[$class: 'CloneOption', noTags: false, reference: '$WORKSPACE/../kodi', shallow: false, timeout: 120],
                            [$class: 'CheckoutOption', timeout: 120], [$class: 'PruneStaleBranch']],
                            userRemoteConfigs: [[credentialsId: 'github-app-xbmc',
                                refspec: "+refs/heads/*:refs/remotes/origin/* +refs/heads/*:refs/remotes/${GITHUB_REPO}/* ${PULLREFSPEC}",
                                url: "https://github.com/${GITHUB_REPO}/xbmc.git"]]
                        ])
                }
            }

            stage('Environment setup') {
                steps {
                    script {
                        env.BUILD_HOST = platformsValid[platform]
                        defenv = readFile(file: "${WORKSPACE}/tools/buildsteps/defaultenv")
                        if (isNdk21(defenv)) {
                            ndkVer = '21.4.7075529'
                        }
                        switch (platform) {
                            case 'Android_arm':
                                os = 'android'
                                env.CONFIGURATION = params.Configuration == 'Default' ? 'Release' : params.Configuration
                                env.CONFIGEXTRA = "-with-sdk-path=${sdkPath} --with-ndk-path=${ndkPath}/${ndkVer}"
                                env.filename = 'kodiapp-armeabi-v7a-' + env.CONFIGURATION.toLowerCase()
                                uploadFileName = 'kodi-BUILDREV-armeabi-v7a'
                                uploadSubDir = uploadFolder == 'nightlies' ? "android/arm/${Revision}" : 'android/arm'
                                verifyHash = "_${CONFIGURATION}_${sdkPath}_${ndkVer}"
                                break
                            case 'Android_arm64':
                                os = 'android'
                                env.CONFIGURATION = params.Configuration == 'Default' ? 'Release' : params.Configuration
                                env.CONFIGEXTRA = "-with-sdk-path=${sdkPath} --with-ndk-path=${ndkPath}/${ndkVer}"
                                env.filename = 'kodiapp-arm64-v8a-' + env.CONFIGURATION.toLowerCase()
                                uploadFileName = 'kodi-BUILDREV-arm64-v8a'
                                uploadSubDir = uploadFolder == 'nightlies' ? "android/arm64-v8a/${Revision}" : 'android/arm64-v8a'
                                verifyHash = "_${CONFIGURATION}_${sdkPath}_${ndkVer}"
                                break
                            case 'Android_x86':
                                os = 'android'
                                env.CONFIGURATION = params.Configuration == 'Default' ? 'Release' : params.Configuration
                                env.CONFIGEXTRA = "-with-sdk-path=${sdkPath} --with-ndk-path=${ndkPath}/${ndkVer}"
                                env.filename = 'kodiapp-x86-' + env.CONFIGURATION.toLowerCase()
                                uploadFileName = 'kodi-BUILDREV-x86'
                                uploadSubDir = uploadFolder == 'nightlies' ? "android/x86/${Revision}" : 'android/x86'
                                verifyHash = "_${CONFIGURATION}_${sdkPath}_${ndkVer}"
                                break
                            case 'Linux_x86_64':
                                os = 'linux'
                                env.CONFIGEXTRA = "--with-toolchain=${toolChain} --with-rendersystem=${renderSystem}"
                                verifyHash = "_${CONFIGURATION}_${renderSystem}"
                                canUpload = false
                                break
                            case 'Linux_arm':
                                os = 'linux'
                                env.CONFIGEXTRA = "--with-toolchain=${toolChain} --with-rendersystem=${renderSystem}"
                                verifyHash = "_${CONFIGURATION}_${renderSystem}"
                                canUpload = false
                                break
                            case 'Linux_arm64':
                                os = 'linux'
                                env.BUILD_HOST = 'aarch64-linux-gnu'
                                env.CONFIGEXTRA = "--with-toolchain=${toolChain} --with-rendersystem=${renderSystem}"
                                verifyHash = "_${CONFIGURATION}_${renderSystem}"
                                canUpload = false
                                break
                            case 'Linux_webos':
                                os = 'linux'
                                env.CONFIGURATION = params.Configuration == 'Default' ? 'Release' : params.Configuration
                                toolchain = toolChain == '/usr' ? '/home/jenkins/webos-tools/arm-webos-linux-gnueabi_sdk-buildroot' : toolChain
                                env.CLITOOL = buildParams.containsKey('cli_path') ? buildParams.cli_path : '/home/jenkins/webos-tools/CLI/bin'
                                env.CONFIGEXTRA = "--with-toolchain=${toolchain}"
                                env.filename = 'org.xbmc.kodi_*_arm.ipk'
                                uploadFileName = 'org.xbmc.kodi_BUILDREV_arm.ipk'
                                uploadSubDir = uploadFolder == 'nightlies' ? "webos/${Revision}" : 'webos'
                                verifyHash = "_${CONFIGURATION}_"
                                break
                        }
                    }
                }
            }

            stage('Check depends') {
                steps {
                    script {
                        /* TODO: fix external library
                            verifyResult = buildHash(
                                path: env.WORKSPACE +'tools/depends', config: params.Configuration, sdk: env.SDK_PATH, ndk: env.NDKVER
                            )
                        */
                        hashStr = 'none'
                        hashFromTag = 'invalid'
                        try {
                            rev = sh(returnStdout: true, script: "git rev-list HEAD --max-count=1 $WORKSPACE/tools/depends")
                            hashStr = rev.trim() + verifyHash
                            hashFromTag = readFile(file: "${WORKSPACE}/tools/depends/.last_success_revision")
                            println 'hashFromTag: ' + hashFromTag
                        }
                        catch (error) {
                            println 'Error verifying depends hash'
                        }

                        env.BUILD_DEPENDS = hashFromTag == hashStr ? 'no' : 'yes'
                        println 'BUILD_DEPENDS: ' + env.BUILD_DEPENDS
                    }
                }
            }

            stage('Build depends') {
                when { equals expected: 'yes', actual: env.BUILD_DEPENDS  }
                steps {
                    script {
                        env.DEBUG_SWITCH = env.CONFIGURATION == 'Release' ? '--disable-debug' : '--enable-debug'
                        sh 'bash -c "\
                          cd $WORKSPACE/tools/depends \
                          && git clean -f -xfd . \
                          && ./bootstrap \
                          && ./configure \
                            --with-tarballs=$TARBALLS_DIR \
                            --host=$BUILD_HOST \
                            --prefix=$WORKSPACE/tools/depends/xbmc-depends \
                            $CONFIGEXTRA \
                            $DEBUG_SWITCH \
                          && make -j$BUILDTHREADS \
                        "'

                        rev = sh(returnStdout: true, script: "git rev-list HEAD --max-count=1 $WORKSPACE/tools/depends")
                        hashStr = rev.trim() + verifyHash
                        writeFile file: "${WORKSPACE}/tools/depends/.last_success_revision", text: "${hashStr}"
                    }
                }
            }

            stage('Build binary addons') {
                when { equals expected: true, actual: params.BUILD_BINARY_ADDONS }
                steps {
                    script {
                        env.FAILED_BUILD_FILENAME = '.last_failed_revision'
                        result = sh returnStdout: true, script: '''
                            echo "building binary addons: $ADDONS"
                            rm -f $WORKSPACE/cmake/.last_failed_revision
                            cd $WORKSPACE/tools/depends/target/binary-addons
                            make -j$BUILDTHREADS ADDONS="$ADDONS" V=1 VERBOSE=1
                          '''

                        hashStr = rev.trim() + verifyHash
                        if (result =~ /Following Addons failed to build/ ) {
                            writeFile file: "${WORKSPACE}/cmake/.last_failed_revision", text: "${hashStr}"
                        }
                        else {
                            writeFile file: "${WORKSPACE}/cmake/.last_success_revision", text: "${hashStr}"
                        }
                    }
                }
            }

            stage('Build kodi') {
                steps {
                    script {
                        sh '''
                          cd $WORKSPACE
                          rm -rf $WORKSPACE/build
                          make -C $WORKSPACE/tools/depends/target/cmakebuildsys
                          cd build
                          make -j$BUILDTHREADS VERBOSE=1
                        '''
                    }
                }
            }

            stage('Run tests') {
                when {
                  equals expected: 'true', actual: env.RUN_TESTS
                  expression { return allowTests }
                }
                steps {
                    sh '''
                      cd $WORKSPACE/build
                      make -j$BUILDTHREADS VERBOSE=1 kodi-test
                      if [ "$Configuration" != "Coverage" ]; then
                        cd $WORKSPACE;build/kodi-test --gtest_output=xml:gtestresults.xml $FILTER_TESTS
                      else
                        cd $WORKSPACE/build;GTEST_OUTPUT="xml:$WORKSPACE/gtestresults.xml" make coverage
                      fi
                    '''
                }
            }

            stage('Package android') {
                when { equals expected: 'android', actual: os  }
                steps {
                    withCredentials([string(credentialsId: 'androidSigningKeyPassword', variable: 'SIGNING_KEY')]) {
                        sh '''
                          cd $WORKSPACE/build
                          export KODI_ANDROID_KEY_ALIAS=kodirelease KODI_ANDROID_KEY_PASSWORD=$SIGNING_KEY
                          export KODI_ANDROID_STORE_PASSWORD=$SIGNING_KEY KODI_ANDROID_STORE_FILE=/home/jenkins/android-tools/signing/kodi-release.keystore
                          export GRADLE_OPTS="-Dorg.gradle.daemon=false"
                          make -j$BUILDTHREADS apk
                        '''
                    }
                    script {
                        buildRev = sh(returnStdout: true, script: 'git show -s --abbrev=8  --pretty=format:"%cs_%h"').replace('-', '').replace('_', '-')
                        tag = env.PULLID ? 'PR' + env.PULLID : env.Revision
                        buildTag = "${buildRev}-${tag}"
                        env.uploadFile = uploadFileName.replace('BUILDREV', buildTag)
                        sh '[ -f $WORKSPACE/${filename}.apk ] && mv $WORKSPACE/${filename}.apk $WORKSPACE/${uploadFile}.apk || echo "Kodi APK not found!"'
                        sh '[ -f $WORKSPACE/${filename}.aab ] && mv $WORKSPACE/${filename}.aab $WORKSPACE/${uploadFile}.aab || :'
                        publishPattern = "${uploadFile}.apk,${uploadFile}.aab"
                        archiveArtifacts artifacts: publishPattern, followSymlinks: false
                    }
                }
            }

            stage('Package webos') {
                when { equals expected: 'Linux_webos', actual: platform }
                steps {
                    script {
                        sh '''
                            export PATH=$PATH:$CLITOOL
                            cd $WORKSPACE/build
                            make -j$BUILDTHREADS ipk
                        '''
                    }
                    script {
                        buildRev = sh(returnStdout: true, script: 'git show -s --abbrev=8  --pretty=format:"%cs_%h"').replace('-', '').replace('_', '-')
                        tag = env.PULLID ? 'PR' + env.PULLID : env.Revision
                        buildTag = "${buildRev}-${tag}"
                        env.uploadFile = uploadFileName.replace('BUILDREV', buildTag.replace('/', ''))
                        sh '[ -f $WORKSPACE/build/${filename} ] && mv $WORKSPACE/build/${filename} $WORKSPACE/${uploadFile} || echo "Kodi IPK not found!"'
                        publishPattern = "${uploadFile}"
                        archiveArtifacts artifacts: publishPattern, followSymlinks: false
                    }
                }
            }

            stage('Upload') {
                when {
                    allOf {
                        equals expected: true, actual: uploadArtifact
                        equals expected: true, actual: canUpload
                    }
                }
                steps {
                    script {
                        echo "uploading ${uploadFile}.*"
                        echo "calling /usr/local/bin/jenkins-move-kodi.sh ${publishPattern} ${uploadFolder}/${uploadSubDir}"
                    }
                    sshPublisher(
                        publishers: [
                            sshPublisherDesc(
                                configName: 'mirrors-upload',
                                transfers: [
                                    sshTransfer(
                                        sourceFiles: "${uploadFile}*"
                                    )
                                ]
                            ),
                            sshPublisherDesc(
                                configName: 'jenkins-move-kodi',
                                transfers: [
                                    sshTransfer(
                                        execCommand: "/usr/local/bin/jenkins-move-kodi.sh ${publishPattern} ${uploadFolder}/${uploadSubDir}"
                                    )
                                ]
                            )
                        ]
                    )
                }
            }
        }
        post {
            always {
                script {
                    if(env.BUILD_BINARY_ADDONS == 'true') {
                      addonStatusBadge(env.WORKSPACE + '/cmake/addons/.success', env.WORKSPACE + '/cmake/addons/.failure')
                    }
                }
                recordIssues filters: [includeFile('xbmc/.*'), excludeFile('tools/depends/.*')], qualityGates: [[threshold: qualityGateThreshold, type: 'TOTAL', unstable: false]], tools: [clang()]
                addEmbeddableBadgeConfiguration(id: '$BUILD_TAG')
            }
        }
    }
}

def addonStatusBadge(pathesSuccess, pathesFailure) {

    println '#------------- BINARY ADDON STATUS ----------------#'
    if (fileExists(pathesSuccess)) {
        addonsOk = readFile file: pathesSuccess
        println 'addonsSucceeded: ' + addonsOk
        manager.listener.logger.println 'GROOVY: binary addons succeeded marker exists!'
        summary = manager.createSummary('accept.png')
        summary.appendText('<h1>The following binary addons were built successfully:</h1><ul>', false)
        addonsOk.split('\n').each {
            line -> summary.appendText('<li><b>' + line + '</b></li>', false)
        }
        summary.appendText('</ul>', false)
    } else {
        println "addon status file ${pathesSuccess} not found"
    }

    if (fileExists(pathesFailure)) {
        addonsFailed = readFile file: pathesFailure
        println 'addonsFailed: ' + addonsFailed
        manager.listener.logger.println 'GROOVY: binary addons failed marker exists!'
        manager.addWarningBadge('Build of binary addons failed.')
        summary = manager.createSummary('warning.gif')
        summary.appendText('<h1>Build of binary addons failed. This is treated as non-fatal. Following addons failed to build:</h1><ul>', false, false, false, 'red')
        addonsFailed.split('\n').each {
            line -> summary.appendText('<li><b>' + line + '</b></li>', false)
        }
        summary.appendText('</ul>', false)
        manager.buildUnstable()
    } else {
        println 'addonsFailed: none'
    }
    println '#--------------------------------------------------#'
}

@NonCPS
def isNdk21(defenv) {
    try {
        def ndk_matcher = defenv =~ /DEFAULT_NDK_VERSION="(\S\S)\S"/
        def default_ndk = ndk_matcher[0][1]
        println "default_ndk: ${default_ndk}"
        if (env.NDK_VERSION != "" && default_ndk == '21') {
            println 'Detected NDK 21 branch'
            return true
        }
    }
    catch (error) {
        println "Error reading default NDK from tools/buildsteps/defaultenv: ${error}"
    }
    return false
}
