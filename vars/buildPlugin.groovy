import hudson.plugins.throttleconcurrents.ThrottleJobProperty;
import com.cwctravel.hudson.plugins.extended_choice_parameter.ExtendedChoiceParameterDefinition;

/**
 * Simple wrapper step for building a plugin
 */
def call(Map addonParams = [:])
{
	def VERSIONS_VALID = [
		'Leia': 'leia',
		'Matrix': 'matrix',
		'Nexus': 'nexus',
	]

	def version = addonParams.containsKey('version') && addonParams.version in VERSIONS_VALID ? addonParams.version : VERSIONS_VALID.keySet()[0]

	def PLATFORMS_VALID = [
		'android-armv7': 'android',
		'android-aarch64': 'android-arm64-v8a',
		'ios-armv7': 'ios',
		'ios-aarch64': 'ios',
		'osx-x86_64': 'osx64',
		'tvos-aarch64': 'tvos',
		'ubuntu-ppa': 'linux',
		'windows-i686': 'windows/win32',
		'windows-x86_64': 'windows/x64'
	]

	List<String> versionsKeys = new ArrayList<String>(VERSIONS_VALID.keySet());
	if (versionsKeys.indexOf(version) >= versionsKeys.indexOf('Matrix'))
	{
		PLATFORMS_VALID.remove('ios-armv7')
	}
	if (versionsKeys.indexOf(version) < versionsKeys.indexOf('Matrix'))
	{
		PLATFORMS_VALID.remove('tvos-aarch64')
	}

	def PLATFORMS_DEPLOY = [
		'android-armv7',
		'android-aarch64',
		'osx-x86_64',
		'ubuntu-ppa',
		'windows-i686',
		'windows-x86_64'
	]
	def UBUNTU_DISTS = [
		'stable': [
			'impish',
			'hirsute',
			'focal',
			'bionic',
		],
		'nightly': [
			'impish',
			'hirsute',
			'focal',
			'bionic'
		]
	]
	def PPAS_VALID = [
		'nightly': 'ppa:team-xbmc/xbmc-nightly',
		'stable': 'ppa:team-xbmc/ppa',
		'wsnipex-test': 'ppa:wsnipex/xbmc-addons-test'
	]
	def PPA_VERSION_MAP = [
		'Matrix': [
			'stable',
		],
		'Nexus': [
			'nightly',
		]
	]

	def ubuntu_distlist = []
	UBUNTU_DISTS.each{ _, dists -> ubuntu_distlist.addAll(dists)}
	def all_ubuntu_dists = ubuntu_distlist.unique().join(',')

	properties([
		buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '5')),
		disableConcurrentBuilds(),
		disableResume(),
		durabilityHint('PERFORMANCE_OPTIMIZED'),
		pipelineTriggers(env.BRANCH_NAME == 'Nexus' ? [cron('@weekly')] : null),
		[$class: 'RebuildSettings', autoRebuild: false, rebuildDisabled: true],
		[$class: 'ThrottleJobProperty', categories: [], limitOneJobWithMatchingParams: false, maxConcurrentPerNode: 0, maxConcurrentTotal: 1, paramsToUseForLimit: '', throttleEnabled: true, throttleOption: 'category'],
		parameters([
			extendedChoice('deployPlatforms', PLATFORMS_DEPLOY.join(','), PLATFORMS_DEPLOY.join(','), 'Platforms to deploy, deploy param from Jenkinsfile is always respected'),
			extendedChoice('PPA', PPAS_VALID.keySet().join(',')+',auto', 'auto', 'PPA to use'),
			extendedChoice('dists', all_ubuntu_dists, all_ubuntu_dists, 'Ubuntu version to build for'),
			string(defaultValue: '1', description: 'debian package revision tag', name: 'TAGREV', trim: true),
			booleanParam(defaultValue: false, description: 'Force upload to PPA', name: 'force_ppa_upload')
		])
	])

	def deployPlatforms = params.deployPlatforms.tokenize(',')
	def platforms = addonParams.containsKey('platforms') && addonParams.platforms.metaClass.respondsTo('each') && addonParams.platforms.every{ p -> p in PLATFORMS_VALID } ? addonParams.platforms : PLATFORMS_VALID.keySet()
	def deploy = addonParams.containsKey('deploy') && addonParams.deploy.metaClass.respondsTo('each') ? addonParams.deploy.findAll{ d -> d in platforms && d in PLATFORMS_DEPLOY && d in deployPlatforms } : PLATFORMS_DEPLOY
	def addon = env.JOB_NAME.tokenize('/')[1]
	/**
	 * Definition in case if an addon source code contains several addons,
	 * then the start name of the various addons with Asterix is given with e.g.
	 * `buildPlugin(archive: 'USED_PREFIX.*', ...)`.
	 */
	def archiveName = addonParams.containsKey('archive') && addonParams.containsKey('archive') != null ? addonParams.archive : addon
	Map tasks = [failFast: false]

	env.Configuration = 'Release'

	currentBuild.result = 'SUCCESS'

	def platformResult = [:]

	for (int i = 0; i < platforms.size(); ++i)
	{
		String platform = platforms[i]

		def category = "binary-addons/${platform}-${version}"
		if (ThrottleJobProperty.fetchDescriptor().getCategories().every{ c -> c.getCategoryName() !=  category})
		{
			ThrottleJobProperty.fetchDescriptor().getCategories().add(new ThrottleJobProperty.ThrottleCategory(category, 1, 0, null));
			ThrottleJobProperty.fetchDescriptor().save()
		}

		if (platform == 'ubuntu-ppa') continue

		tasks[platform] = {
			throttle(["binary-addons/${platform}-${version}"])
			{
				node(platform)
				{
					ws("workspace/binary-addons/kodi-${platform}-${version}")
					{
						platformResult["${platform}"] = 'UNKNOWN'

						try
						{
							stage("prepare (${platform})")
							{
								pwd = pwd()
								kodiBranch = version == "Nexus" ? "master" : version
								checkout([
									changelog: false,
									scm: [
										$class: 'GitSCM',
										branches: [[name: "*/${kodiBranch}"]],
										doGenerateSubmoduleConfigurations: false,
										extensions: [[$class: 'CloneOption', timeout: 20, honorRefspec: true, noTags: true, reference: "${pwd}/../../kodi"]],
										userRemoteConfigs: [[refspec: "+refs/heads/${kodiBranch}:refs/remotes/origin/${kodiBranch}", url: 'https://github.com/xbmc/xbmc.git']]
									]
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

								if (fileExists("cmake/addons/.success"))
								{
									platformResult["${platform}"] = 'SUCCESS'
									echo "Successfully built addon: ${addon}"
								}
								else if (fileExists("cmake/addons/.failure"))
								{
									platformResult["${platform}"] = 'FAILURE'
									error "Failed to build addon: ${addon}"
								}
							}

							stage("archive (${platform})")
							{
								archiveArtifacts artifacts: "cmake/addons/build/zips/${archiveName}+${platform}/${archiveName}-*.zip"
							}

							if (platform in deploy && env.TAG_NAME != null)
							{
								stage("deploy (${platform})")
								{
									echo "Deploying: ${addon} ${env.TAG_NAME}"
									versionFolder = VERSIONS_VALID[version]
									sshPublisher(
										publishers: [
											sshPublisherDesc(
												configName: 'mirrors-upload',
												transfers: [
													sshTransfer(
														removePrefix: 'cmake/addons/build/zips/',
														sourceFiles: "cmake/addons/build/zips/${archiveName}+${platform}/${archiveName}-*.zip"
													)
												]
											),
											sshPublisherDesc(
												configName: 'jenkins-move-addons',
												transfers: [
													sshTransfer(
														execCommand: """jenkins-move-addons.sh ${archiveName} ${versionFolder} ${platform}"""
													)
												]
											)
										]
									)
								}
							}
						}
						catch (error)
						{
							echo "Build failed: ${error}"
							currentBuild.result  = 'FAILURE'
							platformResult["${platform}"] = 'FAILURE'
						}
						finally
						{
							stage("notify")
							{
								slackNotifier(platformResult["${platform}"], platform)
							}
						}
					}
				}
			}
		}
	}

	if ("ubuntu-ppa" in platforms && "ubuntu-ppa" in deploy)
	{
		platform = "ubuntu-ppa"
		tasks[platform] = {
			throttle(["binary-addons/${platform}-${version}"])
			{
				node(PLATFORMS_VALID[platform])
				{
					ws("workspace/binary-addons/kodi-${platform}-${version}")
					{
						def packageversion
						def epoch = 6
						def changespattern = [:]
						def dists = params.dists.tokenize(',')
						def ppas = (params.PPA == "auto" && PPA_VERSION_MAP.containsKey(version)) ? [PPA_VERSION_MAP[version].each{p -> PPAS_VALID[p]}].flatten() : []
						if (ppas.size() == 0)
						{
							params.PPA.tokenize(',').each{p -> if (PPAS_VALID.containsKey(p)) ppas.add(PPAS_VALID[p])}
						}

						platformResult["${platform}"] = 'UNKNOWN'

						try
						{
							stage("clone ${platform}")
							{
								dir("${addon}")
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
							}

							stage("build ${platform}")
							{
								if (params.force_ppa_upload)
								{
									sh "rm -f kodi-*.changes kodi-*.build kodi-*.upload"
								}

								dir("${addon}")
								{
									echo "Ubuntu dists enabled: ${dists} - TAGREV: ${params.TAGREV} - PPA: ${params.PPA}"
									def addonsxml = readFile "${addon}/addon.xml.in"
									packageversion = getVersion(addonsxml)
									debversion = epoch + ":" + packageversion
									echo "Detected PackageVersion: ${packageversion}"
									def changelogin = readFile 'debian/changelog.in'
									def origtarball = 'kodi-' + addon.replace('.', '-') + "_${packageversion}.orig.tar.gz"

									sh "git archive --format=tar.gz -o ../${origtarball} HEAD"

									for (dist in dists)
									{
										dist = dist.trim()
										echo "Building debian-source package for ${dist}"
										def changelog = changelogin.replace('#PACKAGEVERSION#', debversion).replace('#TAGREV#', params.TAGREV).replace('#DIST#', dist)
										def pattern = 'kodi-' + addon.replace('.', '-') + "_${packageversion}-${params.TAGREV}*${dist}_source.changes"
										changespattern.put(dist, pattern)
										writeFile file: "debian/changelog", text: "${changelog}"
										sh "debuild -d -S -k'jenkins (jenkins build bot) <jenkins@kodi.tv>'"
									}
								}

								platformResult["${platform}"] = 'SUCCESS'
							}

							if ((env.TAG_NAME != null && ppas.size() > 0) || params.force_ppa_upload)
							{
								stage("deploy ${platform}")
								{
									def force = params.force_ppa_upload ? '-f' : ''
									def done = 0
									for (ppa in ppas)
									{
										for (dist in changespattern.keySet())
										{
											if (UBUNTU_DISTS[ppa].contains(dist))
											{
												echo "Uploading ${changespattern[dist]} to ${PPAS_VALID[ppa]}"
												sh "dput ${force} ${PPAS_VALID[ppa]} ${changespattern[dist]}"
												done = done + 1

												if (ppas.size() > done)
												{
													echo "Deleting upload log ${changespattern[dist].replace("changes", "ppa.upload")}"
													sh "rm -f ${changespattern[dist].replace("changes", "ppa.upload")}"
												}
											}
										}
									}
								}
							}
						}
						catch (error)
						{
							echo "Build failed: ${error}"
							currentBuild.result = 'FAILURE'
							platformResult["${platform}"] = 'FAILURE'
						}
						finally
						{
							stage("notify")
							{
								slackNotifier(platformResult["${platform}"], platform)
							}
						}
					}
				}
			}
		}
	}
	parallel(tasks)
}

def extendedChoice(name, choices, defaultchoice, desc)
{
	return new ExtendedChoiceParameterDefinition(
	        name /* String name */,
	        ExtendedChoiceParameterDefinition.PARAMETER_TYPE_MULTI_SELECT /* String type */,
	        choices /* String value */,
	        null /* String projectName */,
	        null /* String propertyFile */,
	        null /* String groovyScript */,
	        null /* String groovyScriptFile */,
	        null /* String bindings */,
	        null /* String groovyClasspath */,
	        null /* String propertyKey */,
	        defaultchoice /* String defaultValue */,
	        null /* String defaultPropertyFile */,
	        null /* String defaultGroovyScript */,
	        null /* String defaultGroovyScriptFile */,
	        null /* String defaultBindings */,
	        null /* String defaultGroovyClasspath */,
	        null /* String defaultPropertyKey */,
	        null /* String descriptionPropertyValue */,
	        null /* String descriptionPropertyFile */,
	        null /* String descriptionGroovyScript */,
	        null /* String descriptionGroovyScriptFile */,
	        null /* String descriptionBindings */,
	        null /* String descriptionGroovyClasspath */,
	        null /* String descriptionPropertyKey */,
	        null /* String javascriptFile */,
	        null /* String javascript */,
	        false /* boolean saveJSONParameterToFile*/,
	        false /* boolean quoteValue */,
	        choices.tokenize(',').size(), /* int visibleItemCount */,
	        desc /* String description */,
	        null /* String multiSelectDelimiter */
	)
}

@NonCPS
def getVersion(text)
{
	def matcher = text =~ /version=\"([\d.]+)\"/
	matcher ? matcher.getAt(1)[1] : null
}

def slackNotifier(String buildResult, String platform)
{
	String color

	def STATUS_COLORS = [
		'SUCCESS': 'good',
		'FAILURE': 'danger',
		'UNSTABLE': 'warning',
		'UNKNOWN': 'danger'
	]

	if (!buildResult)
		buildResult = "UNKNOWN"

	slackSend(channel: "#buildserver-addons", color: "${STATUS_COLORS[buildResult]}", message: "${env.JOB_NAME} #${env.BUILD_NUMBER} for ${platform} ${buildResult} (<${env.BUILD_URL}|Open>)")
}
