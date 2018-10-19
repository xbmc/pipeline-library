import hudson.plugins.throttleconcurrents.ThrottleJobProperty;

/**
 * Simple wrapper step for building a plugin
 */
def call(Map params = [:])
{
	properties([
		buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '5')),
		disableConcurrentBuilds(),
		disableResume(),
		durabilityHint('PERFORMANCE_OPTIMIZED'),
		pipelineTriggers(env.BRANCH_NAME == 'master' ? [cron('@weekly')] : []),
		[$class: 'RebuildSettings', autoRebuild: false, rebuildDisabled: true],
		[$class: 'ThrottleJobProperty', categories: [], limitOneJobWithMatchingParams: false, maxConcurrentPerNode: 0, maxConcurrentTotal: 1, paramsToUseForLimit: '', throttleEnabled: true, throttleOption: 'category']
	])

	def PLATFORMS_VALID = [
		'android-armv7': 'android',
		'android-aarch64': 'android-arm64-v8a',
		'ios-armv7': 'ios',
		'ios-aarch64': 'ios',
		'osx-x86_64': 'osx64',
		'windows-i686': 'windows/win32',
		'windows-x86_64': 'windows/x64'
	]
	def VERSIONS_VALID = [
		'master': 'leia',
		'Leia': 'leia'
	]

	def platforms = params.containsKey('platforms') && params.platforms.metaClass.respondsTo('each') && params.platforms.every{ p -> p in PLATFORMS_VALID } ? params.platforms : PLATFORMS_VALID.keySet()
	def version = params.containsKey('version') && params.version in VERSIONS_VALID ? params.version : VERSIONS_VALID.keySet()[0]
	def addon = env.JOB_NAME.tokenize('/')[1]
	Map tasks = [failFast: false]

	env.Configuration = 'Release'

	for (int i = 0; i < platforms.size(); ++i)
	{
		String platform = platforms[i]

		def category = "binary-addons/${platform}-${version}"
		if (ThrottleJobProperty.fetchDescriptor().getCategories().every{ c -> c.getCategoryName() !=  category})
		{
			ThrottleJobProperty.fetchDescriptor().getCategories().add(new ThrottleJobProperty.ThrottleCategory(category, 1, 0, null));
			ThrottleJobProperty.fetchDescriptor().save()
		}

		tasks[platform] = {
			throttle(["binary-addons/${platform}-${version}"])
			{
				node(platform)
				{
					ws("workspace/binary-addons/kodi-${platform}-${version}")
					{
						stage("prepare (${platform})")
						{
							pwd = pwd()
							checkout([
								changelog: false,
								$class: 'GitSCM',
								branches: [[name: "*/${version}"]],
								doGenerateSubmoduleConfigurations: false,
								extensions: [[$class: 'CloneOption', 'honorRefspec': true, 'noTags': true, 'reference': "${pwd}/../../kodi"]],
								userRemoteConfigs: [[refspec: "+refs/heads/${version}:refs/remotes/origin/${version}", url: 'https://github.com/xbmc/xbmc.git']]
							])

							if (isUnix())
							{
								folder = PLATFORMS_VALID[platform]
								sh "WORKSPACE=`pwd` sh -xe ./tools/buildsteps/${folder}/prepare-depends"
								folder = PLATFORMS_VALID[platform]
								sh "WORKSPACE=`pwd`" + (platform == 'ios-aarch64' ? ' DARWIN_ARM_CPU=arm64' : '') + " sh -xe ./tools/buildsteps/${folder}/configure-depends"
								folder = PLATFORMS_VALID[platform]
								sh "WORKSPACE=`pwd` sh -xe ./tools/buildsteps/${folder}/make-native-depends"
								sh "git clean -xffd -- tools/depends/target/binary-addons"
							}
							else
							{
								folder = PLATFORMS_VALID[platform]
								bat "tools/buildsteps/${folder}/prepare-env.bat"
								folder = PLATFORMS_VALID[platform]
								bat "tools/buildsteps/${folder}/download-dependencies.bat"
								bat "git clean -xffd -- tools/depends/target/binary-addons"
							}

							dir("tools/depends/target/binary-addons/${addon}")
							{
								if (env.BRANCH_NAME)
								{
									def scmVars = checkout(scm)
									currentBuild.displayName = scmVars.GIT_BRANCH + '-' + scmVars.GIT_COMMIT.substring(0, 7)
								}
								else if ((env.BRANCH_NAME == null) && (repo))
								{
									git repo
								}
								else
								{
									error 'buildPlugin must be used as part of a Multibranch Pipeline *or* a `repo` argument must be provided'
								}
							}

							dir("tools/depends/target/binary-addons/addons/${addon}")
							{
								writeFile file: "${addon}.txt", text: "${addon} . ."
								writeFile file: 'platforms.txt', text: 'all'
							}
						}

						stage("build (${platform})")
						{
							dir("tools/depends/target/binary-addons")
							{
								if (isUnix())
									sh "make -j $BUILDTHREADS ADDONS='${addon}' ADDONS_DEFINITION_DIR=`pwd`/addons ADDON_SRC_PREFIX=`pwd` EXTRA_CMAKE_ARGS=\"-DPACKAGE_ZIP=ON -DPACKAGE_DIR=`pwd`/../../../../cmake/addons/build/zips\" PACKAGE=1"
							}

							if (!isUnix())
							{
								env.ADDONS_DEFINITION_DIR = pwd().replace('\\', '/') + '/tools/depends/target/binary-addons/addons'
								env.ADDON_SRC_PREFIX = pwd().replace('\\', '/') + '/tools/depends/target/binary-addons'
								folder = PLATFORMS_VALID[platform]
								bat "tools/buildsteps/${folder}/make-addons.bat package ${addon}"
							}

							if (isUnix())
								sh "grep '${addon}' cmake/addons/.success"
						}

						stage("archive (${platform})")
						{
							archiveArtifacts artifacts: "cmake/addons/build/zips/${addon}+${platform}/${addon}-*.zip"
						}

						stage("deploy (${platform})")
						{
							if (platform != 'ios-armv7' && platform != 'ios-aarch64' && env.TAG_NAME != null)
							{
								echo "Deploying: ${addon} ${env.TAG_NAME}"
								versionFolder = VERSIONS_VALID[version]
								sshPublisher(
									publishers: [
										sshPublisherDesc(
											configName: 'Mirrors',
											transfers: [
												sshTransfer(
													execCommand: """\
mkdir -p /home/git/addons-binary/${versionFolder}
chmod 444 upload/${addon}+${platform}/${addon}-*.zip
(mv upload/${addon}+${platform}/ /home/git/addons-binary/${versionFolder}/ || cp upload/${addon}+${platform}/${addon}-*.zip /home/git/addons-binary/${versionFolder}/${addon}+${platform}/) 2> /dev/null
PUBLISHED=\$?
if [ \$PUBLISHED -ne 0 ]; then
	echo `ls upload/${addon}+${platform}/${addon}-*.zip | cut -d / -f 2-` was already published >&2
fi
rm -fr upload/${addon}+${platform}/ 2> /dev/null
exit \$PUBLISHED
""",
													remoteDirectory: 'upload',
													removePrefix: 'cmake/addons/build/zips/',
													sourceFiles: "cmake/addons/build/zips/${addon}+${platform}/${addon}-*.zip"
												)
											]
										)
									]
								)
							}
						}
					}
				}
			}
		}
	}

	parallel(tasks)
}
