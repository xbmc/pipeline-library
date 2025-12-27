import hudson.plugins.throttleconcurrents.ThrottleJobProperty;
import com.cwctravel.hudson.plugins.extended_choice_parameter.ExtendedChoiceParameterDefinition;

/**
 * Simple wrapper step for building a plugin
 */
def call(Map addonParams = [:])
{
	// The Version from master branch of the xbmc repo has to be the last one in this list.
	// This map has to be updated after a new version is branched in the xbmc repo.
	def VERSIONS_VALID = [
		'Matrix': 'matrix',
		'Nexus': 'nexus',
		'Omega': 'omega',
		'Piers': 'piers',
	]
	def KODI_CURRENT_DEV_VERSION = VERSIONS_VALID.keySet()[-1]

	def version = addonParams.containsKey('version') && addonParams.version in VERSIONS_VALID ? addonParams.version : VERSIONS_VALID.keySet()[0]

	def PLATFORMS_VALID = [
		'android-armv7': 'android',
		'android-aarch64': 'android-arm64-v8a',
		'ios-aarch64': 'ios',
		'osx-x86_64': 'osx64',
		'osx-arm64': 'osx-arm64',
		'tvos-aarch64': 'tvos',
		'windows-arm64': 'windows/arm64',
		'windows-i686': 'windows/win32',
		'windows-x86_64': 'windows/x64'
	]

	List<String> versionsKeys = new ArrayList<String>(VERSIONS_VALID.keySet());
	if (versionsKeys.indexOf(version) < versionsKeys.indexOf('Nexus'))
	{
		PLATFORMS_VALID.remove('osx-arm64')
	}
	if (versionsKeys.indexOf(version) < versionsKeys.indexOf('Piers'))
	{
		PLATFORMS_VALID.remove('windows-arm64')
	}

	def PLATFORMS_DEPLOY = [
		'android-armv7',
		'android-aarch64',
		'osx-x86_64',
		'osx-arm64',
		'windows-arm64',
		'windows-i686',
		'windows-x86_64'
	]

	properties([
		buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '5')),
		disableConcurrentBuilds(),
		disableResume(),
		durabilityHint('PERFORMANCE_OPTIMIZED'),
		pipelineTriggers(env.BRANCH_NAME == KODI_CURRENT_DEV_VERSION ? [cron('@weekly')] : null),
		[$class: 'RebuildSettings', autoRebuild: false, rebuildDisabled: true],
		[$class: 'ThrottleJobProperty', categories: [], limitOneJobWithMatchingParams: false, maxConcurrentPerNode: 0, maxConcurrentTotal: 1, paramsToUseForLimit: '', throttleEnabled: true, throttleOption: 'category'],
		parameters([
			extendedChoice('deployPlatforms', PLATFORMS_DEPLOY.join(','), PLATFORMS_DEPLOY.join(','), 'Platforms to deploy, deploy param from Jenkinsfile is always respected'),
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
								kodiBranch = version == KODI_CURRENT_DEV_VERSION ? "master" : version
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
									sh "WORKSPACE=`pwd` sh -xe ./tools/buildsteps/${folder}/configure-depends"
									folder = PLATFORMS_VALID[platform]
									sh "WORKSPACE=`pwd` sh -xe ./tools/buildsteps/${folder}/make-native-depends"
									sh "git clean -xffd -- tools/depends/target/binary-addons"
								}
								else
								{
									env.USE_LESSMSI = "YES"
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
								if (isUnix())
								{
									dir("tools/depends/target/binary-addons")
									{
										sh "make -j $BUILDTHREADS ADDONS='${addon}' ADDONS_DEFINITION_DIR=`pwd`/addons ADDON_SRC_PREFIX=`pwd` EXTRA_CMAKE_ARGS=\"-DPACKAGE_ZIP=ON -DPACKAGE_DIR=`pwd`/../../../../cmake/addons/build/zips\" PACKAGE=1"
									}
								}
								else
								{
									env.ADDONS_DEFINITION_DIR = pwd().replace('\\', '/') + '/tools/depends/target/binary-addons/addons'
									env.ADDON_SRC_PREFIX = pwd().replace('\\', '/') + '/tools/depends/target/binary-addons'
									env._MSPDBSRV_ENDPOINT_ = "${platform}-${version}"
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
