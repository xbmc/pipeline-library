/*
 * Builds an xbmc/xbmc pull request on every platform. Each platform job starts as soon as its own
 * agent is free and reports as its own GitHub check; there is no shared one-PR-at-a-time slot.
 *
 * The platform jobs are started the way BuildMulti-PR's MultiJob phase starts them: the same
 * parameters, marked safe so the freestyle jobs see the ghprb* values, and the GitHub merge commit
 * pinned with a RevisionParameterAction.
 *
 * Each check names its agents, times and the cause of a failure, and annotates the PR's files
 * with the compile errors, failed tests and new warnings it found. A platform no changed file can
 * affect is skipped. A failure the agent caused is retried once on another agent, and an agent
 * that keeps causing them for a job is avoided for that job until a build of it passes there.
 *
 * GitHub's Re-run on a platform check rebuilds that platform alone: init.groovy.d/pr-checks-rerun
 * in xbmc/infrastructure turns the click into a request under $JENKINS_HOME/pr-checks/reruns,
 * which the running build picks up, or a build it starts. Re-run on the summary rebuilds all.
 *
 * Pilot: builds only pull requests whose head commit is authored by PILOT_AUTHOR, the same rule
 * BuildMulti-PR's blackListCommitAuthor uses to skip them.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import hudson.console.ConsoleNote
import hudson.model.AbstractBuild
import hudson.model.AbstractProject
import hudson.model.Action
import hudson.model.Cause
import hudson.model.CauseAction
import hudson.model.Job
import hudson.model.Label
import hudson.model.ParametersAction
import hudson.model.ParametersDefinitionProperty
import hudson.model.Queue
import hudson.model.Result
import hudson.model.Run
import hudson.model.StringParameterValue
import hudson.model.labels.LabelExpression
import hudson.plugins.git.RevisionParameterAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.regex.Matcher
import java.util.regex.Pattern
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.kodi.ci.ExcludeAgents
import org.kohsuke.github.GHCheckRun
import org.kohsuke.github.GHCheckRunBuilder
import org.kohsuke.github.GHPullRequest
import org.kohsuke.github.GHPullRequestReviewBuilder
import org.kohsuke.github.GHPullRequestReviewEvent
import org.kohsuke.github.GHPullRequestReviewState
import org.kohsuke.github.GHRepository
import org.kohsuke.github.GitHubBuilder

@Field final String PILOT_AUTHOR = 'Malard'
@Field final String GITHUB_TOKEN_ID = '13e4b3a0-8fff-4dc6-b283-677ebd4604e3'
@Field final String BOT_LOGIN = 'jenkins4kodi'
@Field final String RETRY_RULES = '/var/jenkins_home/rules/failures.txt'
@Field final String SUMMARY_CHECK = 'BuildMulti-PR'
@Field final String GITHUB_APP_ID = 'github-app-xbmc'
// Shared with init.groovy.d/pr-checks-rerun.groovy, which writes the re-run requests
@Field final String STATE_DIR = 'pr-checks'
@Field final String STATE_LOCK = 'pr-checks-state'
@Field final String EXTERNAL_ID_PREFIX = 'pr-checks:'
@Field final String RERUN_NOTE = 'Re-run of '
// Agent faults in a row on one agent, for one job, before the PR checks avoid that agent for it
@Field final int AVOID_AFTER = 3
@Field final String ALERT_JOB = 'agent-alert'
@Field final int MAX_SUGGESTIONS = 30
@Field String HEAD_SHA = null
@Field Map CHECK_RUNS = [:]

// BuildMulti-PR's ghprb trigger settings
@Field final List SKIP_LABELS = ['No-Jenkins', 'No Jenkins', 'Jenkins: Ignore', 'Stale']
@Field final List SKIP_TARGETS = ['Dharma', 'Eden', 'Eden-ATV2-iOS5', 'Frodo', 'Frodo-ATV2-iOS6', 'Gotham',
    'Gotham_ios8', 'Helix', 'Helix-Android-x86', 'Isengard', 'Isengard-iOS9', 'Jarvis', 'Krypton', 'Leia',
    'Matrix', 'Nexus', 'Omega']
@Field final String SKIP_PHRASE = '.*\\[skip\\W+ci\\].*'
@Field final List EXCLUDED_PATHS = ['\\.clang-format', '\\.gitignore', '\\.github/.*', '.*/\\.clang-format',
    '.*/\\.gitignore', 'addons/.*', 'docs/.*', 'README\\.md', '.*/README\\.md', 'system/.*\\.xml',
    'system/shaders/.*', 'xbmc/*\\.dox']

// BuildMulti-PR's phase jobs as configured live. Targets Leia, Nexus and Omega are never built, so
// only the master conditions remain.
@Field final Map COMMON = [GITHUB_REPO: 'xbmc', UPLOAD_RESULT: 'false', BUILD_BINARY_ADDONS: 'false']
@Field final List PLATFORMS = [
  [check: 'android-arm-docker', job: 'android-arm-docker', family: 'android'],
  [check: 'android-arm64-docker', job: 'android-arm64-docker', family: 'android'],
  [check: 'android-x86-docker', job: 'android-x86-docker', family: 'android', bare: true],
  [check: 'LINUX-64-GL-docker', job: 'linux-docker', family: 'linux',
   params: [RENDER_SYSTEM: 'gl', PLATFORM: 'Linux_x86_64', RUN_TESTS: 'true', FILTER_TESTS: '-TestNetwork.PingHost']],
  [check: 'LINUX-64-GLES-docker', job: 'linux-docker', family: 'linux',
   params: [RENDER_SYSTEM: 'gles', PLATFORM: 'Linux_x86_64', RUN_TESTS: 'true', FILTER_TESTS: '-TestNetwork.PingHost']],
  [check: 'LINUX-arm-GLES', job: 'LINUX', family: 'linux',
   params: [RENDER_SYSTEM: 'gles', BUILD_HOST: 'arm-linux-gnueabihf', CORE_PLATFORM_NAME: 'wayland gbm']],
  [check: 'LINUX-aarch64-GLES-docker', job: 'linux-docker', family: 'linux',
   params: [RENDER_SYSTEM: 'gles', PLATFORM: 'Linux_arm64']],
  [check: 'IOS-ARM64', job: 'IOS-ARM64', family: 'ios', retries: 2],
  [check: 'TVOS', job: 'TVOS', family: 'tvos', retries: 2],
  [check: 'OSX-64', job: 'OSX-64', family: 'osx', retries: 2],
  [check: 'OSX-ARM64', job: 'OSX-ARM64', family: 'osx', retries: 2],
  [check: 'WIN-32', job: 'WIN-32', family: 'windows', notOn: 'master'],
  [check: 'WIN-64', job: 'WIN-64', family: 'windows'],
  [check: 'WIN-ARM64', job: 'WIN-ARM64', family: 'windows', onlyOn: 'master'],
  [check: 'WIN-UWP-64', job: 'WIN-UWP-64', family: 'uwp'],
  [check: 'webos-docker', job: 'webos-docker', family: 'webos', noRevision: true, params: [qualityGateThreshold: '2']],
]
@Field final Map FAMILY_NAMES = [android: 'Android', linux: 'Linux', webos: 'webOS', ios: 'iOS', tvos: 'tvOS',
    osx: 'macOS', windows: 'Windows', uwp: 'UWP']

// Directories only some platforms compile, from cmake/treedata and the platform cmake files. The
// longest matching prefix decides; a file under none of them can affect every platform.
@Field final Map PLATFORM_PATHS = [
  'xbmc/platform/android/': ['android'], 'xbmc/windowing/android/': ['android'],
  'cmake/platform/android/': ['android'], 'cmake/scripts/android/': ['android'],
  'cmake/treedata/android/': ['android'], 'tools/android/': ['android'],
  'tools/buildsteps/android/': ['android'], 'tools/buildsteps/android-arm64-v8a/': ['android'],
  'tools/buildsteps/android-x86_64/': ['android'], 'tools/buildsteps/androidx86/': ['android'],
  'xbmc/platform/linux/': ['linux', 'webos', 'android'],
  'xbmc/platform/posix/': ['android', 'linux', 'webos', 'ios', 'tvos', 'osx'],
  'xbmc/windowing/linux/': ['linux', 'webos'], 'xbmc/windowing/wayland/': ['linux', 'webos'],
  'xbmc/windowing/X11/': ['linux'], 'xbmc/windowing/gbm/': ['linux'],
  'cmake/platform/linux/': ['linux', 'webos'], 'cmake/scripts/linux/': ['linux', 'webos'],
  'cmake/treedata/linux/': ['linux', 'webos'], 'tools/buildsteps/linux/': ['linux', 'webos'],
  'tools/buildsteps/linux-debian/': ['linux', 'webos'], 'tools/Linux/': ['linux', 'webos'],
  'cmake/scripts/webos/': ['webos'], 'tools/webOS/': ['webos'],
  'xbmc/platform/darwin/': ['ios', 'tvos', 'osx'], 'cmake/scripts/darwin/': ['ios', 'tvos', 'osx'],
  'tools/darwin/': ['ios', 'tvos', 'osx'],
  'xbmc/platform/darwin/ios-common/': ['ios', 'tvos'], 'cmake/platform/darwin_embedded/': ['ios', 'tvos'],
  'cmake/scripts/darwin_embedded/': ['ios', 'tvos'], 'cmake/treedata/darwin_embedded/': ['ios', 'tvos'],
  'xbmc/platform/darwin/ios/': ['ios'], 'xbmc/windowing/ios/': ['ios'], 'tools/buildsteps/ios/': ['ios'],
  'xbmc/platform/darwin/tvos/': ['tvos'], 'xbmc/windowing/tvos/': ['tvos'], 'tools/buildsteps/tvos/': ['tvos'],
  'xbmc/platform/darwin/osx/': ['osx'], 'xbmc/windowing/osx/': ['osx'], 'cmake/platform/osx/': ['osx'],
  'cmake/scripts/osx/': ['osx'], 'cmake/treedata/osx/': ['osx'], 'tools/buildsteps/osx64/': ['osx'],
  'tools/buildsteps/osx-arm64/': ['osx'],
  'xbmc/platform/win32/': ['windows', 'uwp'], 'xbmc/rendering/dx/': ['windows', 'uwp'],
  'cmake/scripts/windows/': ['windows', 'uwp'], 'project/BuildDependencies/': ['windows', 'uwp'],
  'project/Win32BuildSetup/': ['windows', 'uwp'], 'tools/buildsteps/windows/': ['windows', 'uwp'],
  'tools/windows/': ['windows', 'uwp'],
  'xbmc/windowing/windows/': ['windows'], 'cmake/platform/windows/': ['windows'],
  'cmake/treedata/windows/': ['windows'],
  'xbmc/platform/win10/': ['uwp'], 'xbmc/windowing/win10/': ['uwp'], 'cmake/platform/windowsstore/': ['uwp'],
  'cmake/scripts/windowsstore/': ['uwp'], 'cmake/treedata/windowsstore/': ['uwp'],
  'xbmc/rendering/gl/': ['linux', 'osx'],
  'xbmc/rendering/gles/': ['android', 'linux', 'webos', 'ios', 'tvos'],
  // No PR check builds FreeBSD or wasm
  'xbmc/platform/freebsd/': [], 'xbmc/platform/wasm/': [], 'xbmc/windowing/wasm/': [],
  'cmake/platform/freebsd/': [], 'cmake/platform/wasm/': [], 'cmake/scripts/freebsd/': [],
  'cmake/scripts/wasm/': [], 'cmake/treedata/freebsd/': [], 'cmake/treedata/wasm/': [],
  'tools/buildsteps/freebsd/': [], 'tools/buildsteps/wasm/': [], 'tools/wasm/': [],
]

// Log lines that mean the agent, not the change, failed the build. %s takes the pattern's group.
@Field final List AGENT_FAULTS = [
  [pattern: 'No available simulator runtimes for platform (\\w+)', title: 'has no %s runtime'],
  [pattern: 'edu[./]hm[./]hafner[./]analysis[./]SecureDigester', title: 'could not load the warnings recorder'],
  [pattern: 'No space left on device', title: 'ran out of disk space'],
  [pattern: 'hudson\\.remoting\\.ChannelClosedException|java\\.nio\\.channels\\.ClosedChannelException|Agent went offline during the build',
   title: 'lost its connection to Jenkins'],
]

def call() {
  properties([
    disableConcurrentBuilds(abortPrevious: true),
    buildDiscarder(logRotator(numToKeepStr: '30')),
  ])

  int prId = env.CHANGE_ID as int
  Map pr = pullRequest(prId)
  String skip = skipReason(pr)
  List files = skip ? [] : prFiles(prId)
  if (!skip && !files.isEmpty() && onlyExcluded(files)) {
    skip = 'Only excluded paths changed'
  }
  if (skip) {
    currentBuild.result = 'NOT_BUILT'
    currentBuild.description = skip
    return
  }
  HEAD_SHA = pr.head
  boolean rerunOnly = isRerunBuild(currentBuild.rawBuild)

  List all = platformsFor(pr.base)
  List platforms
  Map skipped = [:]
  if (rerunOnly) {
    List wanted = takeReruns(env.JOB_NAME, HEAD_SHA, all*.check)
    platforms = all.findAll { wanted.contains(it.check) }
    if (platforms.isEmpty()) {
      currentBuild.result = 'NOT_BUILT'
      currentBuild.description = 'Nothing to re-run'
      return
    }
    currentBuild.description = "Re-run of ${platforms*.check.join(', ')}"
  } else {
    // This build covers every platform anyone asked to re-run
    takeReruns(env.JOB_NAME, HEAD_SHA, all*.check)
    skipped = unaffected(all, files)
    platforms = all.findAll { !skipped.containsKey(it.check) }
    currentBuild.description = pr.title
  }

  Map results = [:]
  for (Map p : platforms) {
    check(p.check, 'QUEUED', null, 'Waiting for the PR checks', env.BUILD_URL)
  }
  for (Map p : all) {
    if (skipped.containsKey(p.check)) {
      check(p.check, 'COMPLETED', 'SKIPPED', skipped[p.check], env.BUILD_URL)
      results[p.check] = 'SKIPPED'
    }
  }
  check(SUMMARY_CHECK, 'IN_PROGRESS', null, 'Checking the pull request', env.BUILD_URL)

  String stopped = null
  String formatDiff = ''
  try {
    Map co = [:]
    node('buildall') {
      ws('workspace/BuildMulti-PR-checks') {
        stopped = preCheckFailure(prId)
        if (!stopped) {
          co = checkoutAndFormat(prId)
        }
      }
    }
    if (stopped) {
      echo "ERROR: ${stopped}"
      return
    }
    formatDiff = co.formatDiff

    Map ctx = [prId: prId, pr: pr, mergeSha: co.mergeSha, changed: changedLines(co.prDiff),
               shifts: lineShifts(co.headDiff)]
    Map live = [running: platforms.size()]
    Map branches = [:]
    for (Map p : platforms) {
      branches[p.check] = branchFor(p, ctx, results, live)
    }
    if (!branches.isEmpty()) {
      parallel branches
    }

    // Re-runs asked for after every platform had finished
    while (true) {
      List again = takeReruns(env.JOB_NAME, HEAD_SHA, all*.check)
      if (again.isEmpty()) {
        break
      }
      Map more = [:]
      live.running = again.size()
      for (Map p : all.findAll { again.contains(it.check) }) {
        CHECK_RUNS.remove(p.check)
        check(p.check, 'QUEUED', null, 'Re-run requested', env.BUILD_URL)
        more[p.check] = branchFor(p, ctx, results, live)
      }
      parallel more
    }
  } catch (FlowInterruptedException e) {
    stopped = 'Cancelled'
    throw e
  } finally {
    for (Map p : platforms) {
      if (!results.containsKey(p.check)) {
        check(p.check, 'COMPLETED', stopped ? 'CANCELED' : 'SKIPPED', stopped ?: 'Not built', env.BUILD_URL)
      }
    }
    // A re-run build holds only some platforms; the PR's state is every platform's latest check
    Map states = results
    try {
      Map latest = latestStates(currentBuild.rawBuild, HEAD_SHA, all*.check)
      if (!latest.isEmpty()) {
        states = latest
      }
    } catch (Exception e) {
      echo "Could not read the PR's checks: ${e}"
    }
    String overall = stopped ? 'ABORTED' : worst(states.values())
    if (!stopped && !rerunOnly) {
      reviewComments(prId, currentBuild.rawBuild, formatDiff).each { echo it }
    }
    check(SUMMARY_CHECK, 'COMPLETED', stopped && stopped != 'Cancelled' ? 'FAILURE' : conclusionOf(overall),
        stopped ?: summaryTitle(states), env.BUILD_URL)
    String own = stopped ? 'ABORTED' : worst(results.values())
    if (own != 'SUCCESS') {
      currentBuild.result = own
    }
    if (!stopped) {
      notifySlack(overall, pr.title)
    }
  }
}

/** A platform's parallel branch: build it, then build it again for each re-run asked for meanwhile. */
Closure branchFor(Map p, Map ctx, Map results, Map live) {
  return {
    while (true) {
      results[p.check] = runPlatform(p, ctx)
      live.running--
      boolean again = false
      waitUntil(initialRecurrencePeriod: 15000, quiet: true) {
        again = takeRerun(env.JOB_NAME, HEAD_SHA, p.check)
        return again || live.running <= 0
      }
      if (!again) {
        return
      }
      live.running++
      CHECK_RUNS.remove(p.check)
      check(p.check, 'QUEUED', null, 'Re-run requested', env.BUILD_URL)
    }
  }
}

/** One platform: start its job, follow it, retry it on an infrastructure failure. */
String runPlatform(Map p, Map ctx) {
  Map params = [:]
  params.putAll(ghprbParams(ctx))
  if (!p.bare) {
    params.putAll(COMMON)
    if (!p.noRevision) {
      params.Revision = "PR${ctx.prId}".toString()
    } else {
      params.ghprbPullId = ctx.prId.toString()
    }
  }
  params.putAll(p.params ?: [:])
  String pin = p.bare ? null : ctx.mergeSha
  int retries = (p.retries ?: 1) as int

  Long itemId = null
  String runId = null
  List avoid = []
  List attempts = []
  try {
    int attempt = 0
    boolean moved = false
    while (true) {
      Map avoided = avoidedAgents(p.job)
      for (String a : avoided.alerts) {
        postAlert(a)
      }
      itemId = schedule(p.job, params, pin, currentBuild.rawBuild, avoid, avoided.agents)
      runId = null
      Map s = [:]
      waitUntil(initialRecurrencePeriod: 5000, quiet: true) {
        s = queueState(itemId)
        return s.state != 'QUEUED'
      }
      if (s.state != 'STARTED') {
        check(p.check, 'COMPLETED', 'CANCELED', 'Removed from the queue', env.BUILD_URL)
        return 'ABORTED'
      }
      runId = s.id
      long waited = s.waited as long
      check(p.check, 'IN_PROGRESS', null, s.name, s.url)
      waitUntil(initialRecurrencePeriod: 15000, quiet: true) {
        s = runState(runId)
        return !s.building
      }
      Map log = scanLog(runId, pin, s.result != 'SUCCESS')
      String agent = s.agent ?: log.agent
      String fault = log.fault ? "${agent ?: 'The agent'} ${log.fault}".toString() : null
      String alert = recordAgentOutcome(p.job, agent, fault, s.result == 'SUCCESS')
      if (alert) {
        postAlert(alert)
      }
      attempts.add([name: s.name, url: s.url, agent: agent, waited: waited, took: s.duration, result: s.result,
                    cause: causeTitle(s.result, log, fault)])

      // GitHub replaces the merge commit whenever the target moves, so a build that queued long
      // enough cannot fetch the one it was pinned to
      if (log.lostPin && attempt < retries) {
        attempt++
        pin = mergeCommit(ctx.prId) ?: pin
        check(p.check, 'IN_PROGRESS', null, "${s.name}: GitHub replaced the merge commit, retrying", s.url)
        continue
      }
      if (fault && !moved && agent) {
        moved = true
        avoid.add(agent)
        if (canAvoid(p.job, avoid)) {
          check(p.check, 'IN_PROGRESS', null, "${s.name}: ${fault}, retrying on another agent", s.url)
          continue
        }
      }
      if (log.rule && attempt < retries) {
        attempt++
        check(p.check, 'IN_PROGRESS', null, "${s.name}: ${s.result}, retrying", s.url)
        continue
      }
      Map r = report(runId, log, ctx, attempts)
      r.notes.each { echo "${s.name}: ${it}" }
      check(p.check, 'COMPLETED', conclusionOf(s.result), r.title, s.url, r.summary, r.annotations)
      return s.result
    }
  } catch (FlowInterruptedException e) {
    stop(itemId, runId)
    check(p.check, 'COMPLETED', 'CANCELED', 'Cancelled', env.BUILD_URL)
    throw e
  }
}

/** The parameters BuildMulti-PR passes down as its own (MultiJob "current parameters"). */
@NonCPS
Map ghprbParams(Map ctx) {
  return [
    ghprbActualCommit: ctx.pr.head,
    ghprbPullId: ctx.prId.toString(),
    ghprbTargetBranch: ctx.pr.base,
    ghprbGhRepository: 'xbmc/xbmc',
    ghprbPullAuthorLogin: ctx.pr.login,
    sha1: "origin/pr/${ctx.prId}/merge".toString(),
    BUILD_BINARY_ADDONS: 'false',
  ]
}

@NonCPS
List platformsFor(String base) {
  return PLATFORMS.findAll { p -> (!p.onlyOn || p.onlyOn == base) && (!p.notOn || p.notOn != base) }
}

/** BuildMulti-PR's ghprb trigger rules, the pilot's author rule first so other PRs cost one call. */
@NonCPS
String skipReason(Map pr) {
  if (!pr.author.equalsIgnoreCase(PILOT_AUTHOR)) {
    return "Not in the pilot (head commit by ${pr.author})"
  }
  if (pr.closed) {
    return "PR #${pr.number} is ${pr.merged ? 'merged' : 'closed'}, nothing to build"
  }
  if (SKIP_TARGETS.contains(pr.base)) {
    return "Target ${pr.base} is not built"
  }
  String label = pr.labels.find { SKIP_LABELS.contains(it) }
  if (label) {
    return "Labelled ${label}"
  }
  Pattern phrase = Pattern.compile(SKIP_PHRASE, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
  if (phrase.matcher(pr.title).matches() || phrase.matcher(pr.body).matches()) {
    return 'Skip phrase'
  }
  return null
}

@NonCPS
boolean excluded(String file) {
  return EXCLUDED_PATHS.any { file ==~ it }
}

@NonCPS
boolean onlyExcluded(List files) {
  return files.every { excluded(it) }
}

/** The platforms none of the changed files can affect, each with its check title. */
@NonCPS
Map unaffected(List platforms, List files) {
  Set families = new HashSet()
  for (String f : files) {
    if (excluded(f)) {
      continue
    }
    String prefix = PLATFORM_PATHS.keySet().findAll { f.startsWith(it) }.max { it.length() }
    if (prefix == null) {
      return [:]
    }
    families.addAll(PLATFORM_PATHS[prefix])
  }
  List names = FAMILY_NAMES.findAll { k, v -> families.contains(k) }.collect { k, v -> v }
  String only = names.isEmpty() ? 'code no PR check builds' :
      (names.size() == 1 ? names[0] : names[0..-2].join(', ') + ' and ' + names[-1]) + ' code'
  Map skipped = [:]
  platforms.findAll { !families.contains(it.family) }.each { p ->
    skipped[p.check] = "Not built: this PR changes only ${only}".toString()
  }
  return skipped
}

/** Checks out the merge commit, writes the format diffs and returns what later steps need of it. */
Map checkoutAndFormat(int prId) {
  checkout([$class: 'GitSCM',
    branches: [[name: "origin/pr/${prId}/merge"]],
    userRemoteConfigs: [[url: 'https://github.com/xbmc/xbmc', credentialsId: 'github-app-xbmc',
      refspec: "+refs/pull/${prId}/merge:refs/remotes/origin/pr/${prId}/merge"]],
    extensions: [
      [$class: 'CloneOption', shallow: false, noTags: false, reference: "${env.WORKSPACE}/../kodi", depth: 0,
       honorRefspec: true],
      [$class: 'PruneStaleBranch'],
      [$class: 'CleanBeforeCheckout'],
    ]])
  sh(label: 'clang-format diffs', script: '''{ set +e; } 2>/dev/null
{ set +x; } 2>/dev/null
rm -f PR*.diff
file=PR$CHANGE_ID.diff
git-clang-format --diff HEAD~ HEAD > "$file"
if [ -f "$file" -a ! -s "$file" ] || [ "$(cat $file)" = "no modified files to format" ]
then
        rm "$file"
fi

i=0
for commit in $(git log --format=%H --no-merges --reverse HEAD~..HEAD)
do
        i=$((i+1))
        abbrev=$(git show --format="%h" -s "$commit")
        parent=$(git show --format="%P" -s "$commit")
        file=PR$CHANGE_ID-$(printf %04d $i)-$abbrev-$(git show --format="%f" -s "$commit" | head -c 34).diff
        git-clang-format --diff "$parent" "$commit" -- xbmc > "$file"
        if [ -f "$file" -a ! -s "$file" ] || [ "$(cat $file)" = "no modified files to format" ]
        then
                rm "$file"
        fi
done
''')
  archiveArtifacts artifacts: 'PR*.diff', allowEmptyArchive: true
  // HEAD^2 is the PR's head, where review comments and annotations sit
  String formatDiff = sh(returnStdout: true, label: 'clang-format at the PR head', script: '''{ set +x; } 2>/dev/null
git-clang-format --diff "$(git merge-base HEAD^1 HEAD^2)" HEAD^2 2>/dev/null || true
''')
  String prDiff = sh(returnStdout: true, script: '{ set +x; } 2>/dev/null; git diff -U0 --no-color --no-ext-diff HEAD~ HEAD')
  // How the target's changes since the PR's base move the lines of the files the PR changed
  String headDiff = sh(returnStdout: true, label: 'line offsets to the PR head', script: '''{ set +x; } 2>/dev/null
git diff --name-only -z HEAD~ HEAD | xargs -0 -r git diff -U0 --no-color --no-ext-diff HEAD^2 HEAD --
''')
  String mergeSha = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
  return [mergeSha: mergeSha, formatDiff: formatDiff, prDiff: prDiff, headDiff: headDiff]
}

@NonCPS
String worst(Collection results) {
  if (results.isEmpty() || results.contains(null) || results.contains('ABORTED') || results.contains('RUNNING')) {
    return 'ABORTED'
  }
  if (results.contains('FAILURE')) {
    return 'FAILURE'
  }
  if (results.contains('UNSTABLE')) {
    return 'UNSTABLE'
  }
  return 'SUCCESS'
}

@NonCPS
String conclusionOf(String result) {
  switch (result) {
    case 'SUCCESS':
      return 'SUCCESS'
    case 'ABORTED':
    case 'NOT_BUILT':
      return 'CANCELED'
    default:
      return 'FAILURE'
  }
}

@NonCPS
String summaryTitle(Map results) {
  Map counts = results.values().countBy { it ?: 'UNKNOWN' }
  return counts.collect { k, v -> "${v} ${k.toLowerCase()}" }.join(', ') ?: 'No platform built'
}

void notifySlack(String result, String title) {
  Map colours = [SUCCESS: 'good', UNSTABLE: 'warning', FAILURE: 'danger']
  if (!colours.containsKey(result)) {
    return
  }
  slackSend color: colours[result],
      message: "${env.JOB_NAME} - #${env.BUILD_NUMBER} ${result.toLowerCase().capitalize()} (<${env.BUILD_URL}|Open>)\n${title}"
}

void check(String name, String status, String conclusion, String title, String url) {
  check(name, status, conclusion, title, url, null, [])
}

/** Creates or updates the PR head's check run of that name; a GitHub error never fails the build. */
void check(String name, String status, String conclusion, String title, String url, String summary,
    List annotations) {
  try {
    CHECK_RUNS[name] = publishCheck(currentBuild.rawBuild, CHECK_RUNS[name] as Long, name, HEAD_SHA, status,
        conclusion, title, url, summary, annotations, EXTERNAL_ID_PREFIX + env.JOB_NAME)
  } catch (Exception e) {
    echo "Could not publish check ${name}: ${e}"
    if (!annotations.isEmpty()) {
      // A rejected annotation must not leave the check unfinished
      check(name, status, conclusion, title, url, summary, [])
    }
  }
}

/////////////////////////////
// Reading a platform build

/** The lines each file gained in `git diff -U0` output, as [first, last] ranges. */
@NonCPS
Map changedLines(String diff) {
  Map changed = [:]
  String file = null
  diff.eachLine { line ->
    if (line.startsWith('+++ ')) {
      file = line == '+++ /dev/null' ? null : line.substring(4).replaceFirst('^b/', '')
      return
    }
    def hunk = file ? (line =~ /^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/) : null
    if (hunk != null && hunk.find()) {
      int start = hunk.group(1) as int
      int count = hunk.group(2) != null ? hunk.group(2) as int : 1
      if (count > 0) {
        changed.get(file, []).add([start, start + count - 1])
      }
    }
  }
  return changed
}

/** Each file's `git diff -U0 HEAD^2 HEAD` hunks as [headStart, headCount, mergeStart, mergeCount]. */
@NonCPS
Map lineShifts(String diff) {
  Map shifts = [:]
  String file = null
  diff.eachLine { line ->
    if (line.startsWith('+++ ')) {
      file = line == '+++ /dev/null' ? null : line.substring(4).replaceFirst('^b/', '')
      return
    }
    def hunk = file ? (line =~ /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/) : null
    if (hunk != null && hunk.find()) {
      shifts.get(file, []).add([hunk.group(1) as int, hunk.group(2) != null ? hunk.group(2) as int : 1,
                                hunk.group(3) as int, hunk.group(4) != null ? hunk.group(4) as int : 1])
    }
  }
  return shifts
}

/** A merge commit line's number at the PR head, or null for a line the target changed. */
@NonCPS
Integer toHeadLine(List hunks, int line) {
  int delta = 0
  for (List h : hunks ?: []) {
    int headCount = h[1]
    int mergeStart = h[2]
    int mergeCount = h[3]
    if (mergeCount > 0 && line >= mergeStart && line < mergeStart + mergeCount) {
      return null
    }
    boolean before = mergeCount > 0 ? mergeStart + mergeCount - 1 < line : mergeStart < line
    if (!before) {
      break
    }
    delta += headCount - mergeCount
  }
  return line + delta
}

/** Every issue the warnings plugin recorded on a run, as plain maps. */
@NonCPS
List issuesOf(String runId) {
  Run run = Run.fromExternalizableId(runId)
  List issues = []
  run.getAllActions().findAll { it.getClass().name == 'io.jenkins.plugins.analysis.core.model.ResultAction' }.each { a ->
    a.result.issues.each { i ->
      issues.add([file: i.fileName, line: i.lineStart, message: i.message, tool: a.id])
    }
  }
  return issues
}

/** The issues that sit on a line this PR added or changed, one per file, line and message. */
@NonCPS
List newWarnings(List issues, Map changed) {
  Map found = [:]
  issues.each { i ->
    String file = (i.file ?: '').replace('\\', '/')
    String path = changed.keySet().find { p -> file.equalsIgnoreCase(p) || file.toLowerCase().endsWith('/' + p.toLowerCase()) }
    int line = (i.line ?: 0) as int
    if (path && changed[path].any { r -> line >= r[0] && line <= r[1] }) {
      found.putIfAbsent("${path}:${line}:${i.message}".toString(), [path: path, line: line, message: i.message ?: ''])
    }
  }
  return found.values().sort { a, b -> a.path <=> b.path ?: a.line <=> b.line } as List
}

@Field final Pattern AGENT_LINE = ~/^(?:Building remotely on|Running on) (\S+)/
@Field final Pattern GCC_ERROR = ~/^(\S+?):(\d+):(?:\d+:)? (?:fatal )?error: (.+)$/
@Field final Pattern MSVC_ERROR = ~/^\s*(?:\d+>)?(\S.*?)\((\d+)(?:,\d+)?\)\s*: (?:fatal )?error (?:C|LNK)\d+: (.+?)(?: \[[^\]]*\])*$/
@Field final Pattern MSVC_TEST = ~/^\s*(\S.*?)\((\d+)\): error: (.+)$/
@Field final Pattern GCC_TEST = ~/^(\S+?):(\d+): Failure$/
@Field final Pattern UNLOCATED = ~/\berror (?:LNK|C)\d+:|undefined reference to|Undefined symbols for architecture|ld: symbol\(s\) not found/
@Field final Pattern TEST_RUN = ~/^\[ RUN      \] (\S+)/
@Field final Pattern TEST_FAILED = ~/^\[  FAILED  \] ([A-Za-z_][\w\/]*\.[\w\/]+)/
@Field final Pattern GATE = ~/\(Actual value: (\d+), Quality gate: (\d+)(?:\.\d+)?\)/

/**
 * One pass over a platform build's log: its agent, and for a failed build an agent fault, a lost
 * pinned commit, a retry rule match, compile errors, failed tests and a missed quality gate.
 */
@NonCPS
Map scanLog(String runId, String pin, boolean failed) {
  Run run = Run.fromExternalizableId(runId)
  return scanLines(new BufferedReader(run.logReader), pin, failed, failed ? retryRules() : [])
}

@NonCPS
Map scanLines(BufferedReader reader, String pin, boolean failed, List<Pattern> rules) {
  List faults = AGENT_FAULTS.collect { [pattern: Pattern.compile(it.pattern), title: it.title] }
  String pinFailure = pin ? "rev-parse ${pin}^{commit}\" returned status code 128".toString() : null
  Map out = [agent: null, fault: null, lostPin: false, rule: null, errors: [], unlocated: [], tests: [],
             failedTests: [], gate: null]
  Set seen = new HashSet()
  String test = null
  Map pending = null
  try {
    String raw
    while ((raw = reader.readLine()) != null) {
      String line = ConsoleNote.removeNotes(raw).replaceAll('\u001B\\[[0-9;]*m', '')
      if (out.agent == null) {
        Matcher m = AGENT_LINE.matcher(line)
        if (m.find()) {
          out.agent = m.group(1)
        }
      }
      if (!failed) {
        if (out.agent != null) {
          break
        }
        continue
      }
      if (pending != null) {
        if (line.startsWith('[') || line.trim().isEmpty() || pending.lines >= 4) {
          out.tests.add([file: pending.file, line: pending.line, test: pending.test, message: pending.message])
          pending = null
        } else {
          pending.message = (pending.message ? pending.message + ' ' : '') + line.trim()
          pending.lines++
          continue
        }
      }
      if (out.fault == null) {
        for (Map f : faults) {
          Matcher m = f.pattern.matcher(line)
          if (m.find()) {
            out.fault = m.groupCount() > 0 ? String.format(f.title, m.group(1)) : f.title
            break
          }
        }
      }
      if (pinFailure && line.contains(pinFailure)) {
        out.lostPin = true
      }
      if (out.rule == null) {
        for (Pattern rule : rules) {
          if (rule.matcher(line).find()) {
            out.rule = line.trim().take(200)
            break
          }
        }
      }
      if (line.startsWith('[')) {
        Matcher m = TEST_RUN.matcher(line)
        if (m.find()) {
          test = m.group(1)
          continue
        }
        m = TEST_FAILED.matcher(line)
        if (m.find()) {
          if (!out.failedTests.contains(m.group(1)) && out.failedTests.size() < 100) {
            out.failedTests.add(m.group(1))
          }
          test = null
          continue
        }
        if (line.startsWith('[       OK ]')) {
          test = null
        }
        if (line.contains('Quality gate') && line.contains('Failed')) {
          m = GATE.matcher(line)
          if (m.find() && out.gate == null) {
            out.gate = [actual: m.group(1) as int, allowed: m.group(2) as int]
          }
        }
        continue
      }
      if (!line.contains('rror') && !line.contains('Failure') && !line.contains('undefined') &&
          !line.contains('Undefined') && !line.contains('ld: ')) {
        continue
      }
      Matcher m = GCC_TEST.matcher(line)
      if (m.find()) {
        pending = [file: m.group(1), line: m.group(2) as int, test: test, message: '', lines: 0]
        continue
      }
      m = MSVC_ERROR.matcher(line)
      if (!m.find()) {
        m = GCC_ERROR.matcher(line)
        if (!m.find()) {
          m = null
        }
      }
      if (m != null) {
        String key = "${m.group(1)}:${m.group(2)}:${m.group(3)}".toString()
        if (seen.add(key) && out.errors.size() < 200) {
          out.errors.add([file: m.group(1), line: m.group(2) as int, message: m.group(3).trim()])
        }
        continue
      }
      m = MSVC_TEST.matcher(line)
      if (m.find() && test != null) {
        pending = [file: m.group(1), line: m.group(2) as int, test: test, message: m.group(3).trim(), lines: 0]
        continue
      }
      if (UNLOCATED.matcher(line).find()) {
        String msg = line.trim().take(300)
        if (seen.add(msg) && out.unlocated.size() < 10) {
          out.unlocated.add(msg)
        }
      }
    }
  } finally {
    reader.close()
  }
  if (pending != null) {
    out.tests.add([file: pending.file, line: pending.line, test: pending.test, message: pending.message])
  }
  return out
}

@NonCPS
List<Pattern> retryRules() {
  File f = new File(RETRY_RULES)
  return f.exists() ? f.readLines().findAll { it.trim() }.collect { Pattern.compile(it) } : []
}

@NonCPS
String plural(int n, String what) {
  return "${n} ${what}${n == 1 ? '' : 's'}".toString()
}

/** Why a build failed, in a few words, the agent's fault first. */
@NonCPS
String causeTitle(String result, Map log, String fault) {
  if (result == 'SUCCESS') {
    return null
  }
  if (fault) {
    return "infrastructure: ${fault}".toString()
  }
  if (!log.errors.isEmpty()) {
    return plural(log.errors.size(), 'compile error')
  }
  if (!log.failedTests.isEmpty() || !log.tests.isEmpty()) {
    int n = log.failedTests.size() ?: log.tests*.test.unique().size()
    return "${plural(n, 'test')} failed".toString()
  }
  if (log.gate) {
    return "${plural(log.gate.actual, 'warning')}, the quality gate fails at ${log.gate.allowed}".toString()
  }
  if (log.lostPin) {
    return 'infrastructure: GitHub replaced the merge commit while the build queued'
  }
  if (log.rule) {
    return 'infrastructure: a known failure, see the log'
  }
  if (!log.unlocated.isEmpty()) {
    return 'link errors'
  }
  return null
}

/** Maps a path from a build log to the repository path it names, by the longest matching suffix. */
@NonCPS
String repoPath(String raw, Map index) {
  String path = raw.replace('\\', '/')
  String lower = path.toLowerCase()
  if (index.containsKey(lower)) {
    return index[lower]
  }
  int at = lower.indexOf('/')
  while (at >= 0) {
    String tail = lower.substring(at + 1)
    if (index.containsKey(tail)) {
      return index[tail]
    }
    at = lower.indexOf('/', at + 1)
  }
  return null
}

/** The blob of every file of a commit, by path. */
@NonCPS
Map blobs(String sha) {
  Map out = [:]
  repo().getTreeRecursive(sha, 1).getTree().each { e ->
    if (e.getType() == 'blob') {
      out[e.getPath()] = e.getSha()
    }
  }
  return out
}

/** The check's title, its summary table and lists, and its annotations at the PR head. */
@NonCPS
Map report(String runId, Map log, Map ctx, List attempts) {
  List notes = []
  List warnings = []
  try {
    warnings = newWarnings(issuesOf(runId), ctx.changed)
  } catch (Exception e) {
    notes.add("Could not read the warnings: ${e}".toString())
  }

  List errors = log.errors.collect { it + [path: null, at: null] }
  List tests = log.tests.collect { it + [path: null, at: null] }
  if (!errors.isEmpty() || !tests.isEmpty()) {
    try {
      Map merge = blobs(ctx.mergeSha)
      Map head = blobs(HEAD_SHA)
      Map index = [:]
      merge.keySet().each { index[it.toLowerCase()] = it }
      (errors + tests).each { e ->
        e.path = repoPath(e.file, index)
        if (e.path == null) {
          return
        }
        if (ctx.changed.containsKey(e.path)) {
          e.at = toHeadLine(ctx.shifts[e.path], e.line)
        } else if (head[e.path] == merge[e.path]) {
          e.at = e.line
        }
      }
    } catch (Exception e) {
      notes.add("Could not place the errors in the PR's files: ${e}".toString())
    }
  }

  List annotations = []
  errors.findAll { it.at != null }.each { e ->
    annotations.add([path: e.path, line: e.at, level: 'FAILURE', title: 'Compile error', message: e.message])
  }
  tests.findAll { it.at != null }.each { t ->
    annotations.add([path: t.path, line: t.at, level: 'FAILURE', title: t.test ? "${t.test} failed".toString() : 'Test failed',
                     message: t.message ?: 'Test failure'])
  }
  warnings.each { w ->
    Integer at = toHeadLine(ctx.shifts[w.path], w.line as int)
    if (at != null) {
      annotations.add([path: w.path, line: at, level: 'WARNING', title: 'New warning', message: w.message ?: 'warning'])
    }
  }

  Map last = attempts[-1]
  String title = "${last.name}: ${last.result}".toString()
  if (last.cause) {
    title += " -- ${last.cause}"
  }
  if (!warnings.isEmpty()) {
    title += ", ${plural(warnings.size(), 'new warning')}"
  }
  return [title: title, summary: summary(attempts, log, errors, tests, warnings), annotations: annotations,
          notes: notes]
}

@NonCPS
String cell(String s) {
  return (s ?: '').replace('|', '\\|').replace('\n', ' ')
}

@NonCPS
String where(Map e) {
  String path = e.path ?: e.file.replace('\\', '/').replaceFirst('^.*/workspace/[^/]+/', '')
  return "`${path}:${e.line}`".toString()
}

@NonCPS
String summary(List attempts, Map log, List errors, List tests, List warnings) {
  List out = ['| Build | Agent | Queued | Took | Result |', '|---|---|---|---|---|']
  attempts.each { a ->
    out.add("| [${cell(a.name)}](${a.url}console) | ${cell(a.agent ?: 'unknown')} | " +
        "${hudson.Util.getTimeSpanString(a.waited as long)} | ${hudson.Util.getTimeSpanString(a.took as long)} | " +
        "${cell(a.cause ? "${a.result}: ${a.cause}" : a.result)} |")
  }
  if (!errors.isEmpty()) {
    out.add("\n**${plural(errors.size(), 'compile error')}**\n")
    errors.take(50).each { e -> out.add("- ${where(e)} ${e.message.take(300)}") }
    if (errors.size() > 50) {
      out.add("- and ${errors.size() - 50} more")
    }
  } else if (!log.unlocated.isEmpty()) {
    out.add('\n**Link errors**\n')
    log.unlocated.each { out.add("- `${it}`") }
  }
  if (!log.failedTests.isEmpty() || !tests.isEmpty()) {
    List names = log.failedTests ?: tests*.test.findAll { it }.unique()
    out.add("\n**${plural(names.size(), 'failed test')}**\n")
    names.take(50).each { n ->
      Map t = tests.find { it.test == n }
      out.add("- `${n}`" + (t ? " at ${where(t)}: ${t.message.take(300)}" : ''))
    }
  }
  if (!warnings.isEmpty()) {
    out.add("\n**${plural(warnings.size(), 'new warning')} on lines this PR changed**\n")
    warnings.take(100).each { w -> out.add("- `${w.path}:${w.line}` ${w.message}") }
    if (warnings.size() > 100) {
      out.add("- and ${warnings.size() - 100} more")
    }
  }
  return out.join('\n')
}

/////////////////////////////
// State shared with the re-run hook and between builds, under $JENKINS_HOME/pr-checks

@NonCPS
File stateFile(String name) {
  File dir = new File(Jenkins.get().rootDir, STATE_DIR)
  dir.mkdirs()
  return new File(dir, name)
}

@NonCPS
void writeAtomically(File f, String text) {
  File tmp = new File(f.path + '.tmp')
  tmp.setText(text, 'UTF-8')
  Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

@NonCPS
File rerunFile(String jobName) {
  File dir = stateFile('reruns')
  dir.mkdirs()
  return new File(dir, jobName.replaceAll('[^A-Za-z0-9.-]', '_'))
}

/** Takes the re-run requests for these checks at this head; requests for another head are dropped. */
@NonCPS
List takeReruns(String jobName, String sha, List checks) {
  synchronized (STATE_LOCK.intern()) {
    File f = rerunFile(jobName)
    if (!f.exists()) {
      return []
    }
    List taken = []
    List kept = []
    f.readLines('UTF-8').findAll { it.trim() }.each { line ->
      List parts = line.split(' ', 3) as List
      if (parts.size() < 2 || parts[0] != sha) {
        return
      }
      if (checks.contains(parts[1])) {
        if (!taken.contains(parts[1])) {
          taken.add(parts[1])
        }
      } else {
        kept.add(line)
      }
    }
    if (kept.isEmpty()) {
      f.delete()
    } else {
      writeAtomically(f, kept.join('\n') + '\n')
    }
    return taken
  }
}

@NonCPS
boolean takeRerun(String jobName, String sha, String check) {
  return !takeReruns(jobName, sha, [check]).isEmpty()
}

@NonCPS
boolean isRerunBuild(Run build) {
  Cause.RemoteCause cause = build.getCause(Cause.RemoteCause)
  return cause != null && (cause.note ?: '').startsWith(RERUN_NOTE)
}

@NonCPS
Map readFaults() {
  File f = stateFile('agent-faults.json')
  Map faults = [:]
  if (f.exists() && f.length() > 0) {
    new JsonSlurper().parse(f, 'UTF-8').each { k, v -> faults[k] = new HashMap(v) }
  }
  return faults
}

@NonCPS
void writeFaults(Map faults) {
  writeAtomically(stateFile('agent-faults.json'), JsonOutput.prettyPrint(JsonOutput.toJson(faults)))
}

@NonCPS
boolean canMoveJob(String jobName) {
  return canMove(Jenkins.get().getItemByFullName(jobName, Job))
}

@NonCPS
boolean canMove(Job job) {
  return job instanceof AbstractProject && ((AbstractProject) job).getAssignedLabel() != null
}

/**
 * Counts an agent fault against the agent for that job, or clears its count when a build passed.
 * Returns the alert to post when the agent starts or stops being avoided.
 */
@NonCPS
String recordAgentOutcome(String jobName, String agent, String fault, boolean passed) {
  if (!agent || (!fault && !passed)) {
    return null
  }
  synchronized (STATE_LOCK.intern()) {
    Map faults = readFaults()
    String key = "${jobName}|${agent}".toString()
    Map entry = faults[key]
    if (passed) {
      if (entry == null) {
        return null
      }
      faults.remove(key)
      writeFaults(faults)
      return entry.alerted ? ":large_green_circle: A ${jobName} build passed on ${agent}; the PR checks send it ${jobName} builds again".toString() : null
    }
    entry = entry ?: [count: 0, since: System.currentTimeMillis()]
    entry.count = (entry.count as int) + 1
    entry.last = System.currentTimeMillis()
    entry.reason = fault
    String alert = null
    if (entry.count >= AVOID_AFTER && !entry.alerted) {
      entry.alerted = true
      boolean movable = canMoveJob(jobName)
      alert = ":warning: ${entry.count} ${jobName} builds in a row failed on ${agent} for the agent's own reason: ${fault}." +
          (movable ? " The PR checks send ${jobName} builds to other agents until one passes there." : '')
    }
    faults[key] = entry
    writeFaults(faults)
    return alert
  }
}

/** The agents the PR checks avoid for a job, after dropping any where a build of it has since passed. */
@NonCPS
Map avoidedAgents(String jobName) {
  synchronized (STATE_LOCK.intern()) {
    Map faults = readFaults()
    List agents = []
    List alerts = []
    Job job = Jenkins.get().getItemByFullName(jobName, Job)
    boolean changed = false
    faults.findAll { k, v -> k.startsWith(jobName + '|') && (v.count as int) >= AVOID_AFTER }.each { k, v ->
      String agent = k.substring(jobName.length() + 1)
      boolean passed = job != null && job.getBuilds().limit(50).any { b ->
        b instanceof AbstractBuild && ((AbstractBuild) b).getBuiltOnStr() == agent &&
            b.getResult() == Result.SUCCESS && b.getStartTimeInMillis() > (v.last as long)
      }
      if (passed) {
        faults.remove(k)
        changed = true
        alerts.add(":large_green_circle: A ${jobName} build passed on ${agent}; the PR checks send it ${jobName} builds again".toString())
      } else {
        agents.add(agent)
      }
    }
    if (changed) {
      writeFaults(faults)
    }
    return [agents: agents, alerts: alerts]
  }
}

@NonCPS
void postAlert(String message) {
  def job = Jenkins.get().getItemByFullName(ALERT_JOB)
  if (job != null) {
    job.scheduleBuild2(0, new ParametersAction([new StringParameterValue('MESSAGE', message),
                                                new StringParameterValue('COLOR', message.startsWith(':warning:') ? 'warning' : 'good')]))
  }
}

/** The job's label without these agents, or null if no online agent would be left to build it. */
@NonCPS
Label labelAvoiding(Job job, List agents) {
  if (!canMove(job) || agents.isEmpty()) {
    return null
  }
  Label label = ((AbstractProject) job).getAssignedLabel()
  for (String a : agents) {
    label = new LabelExpression.And(label, new LabelExpression.Not(Jenkins.get().getLabelAtom(a)))
  }
  label = Jenkins.get().getLabel(label.getExpression())
  return label.getNodes().any { it.toComputer()?.isOnline() } ? label : null
}

@NonCPS
boolean canAvoid(String jobName, List agents) {
  return labelAvoiding(Jenkins.get().getItemByFullName(jobName, Job), agents) != null
}

/////////////////////////////
// Jenkins and GitHub API calls, outside CPS

@NonCPS
def appGitHub(Run build) {
  def credentials = org.jenkinsci.plugins.github_branch_source.Connector.lookupScanCredentials(
      build.parent, 'https://api.github.com', GITHUB_APP_ID, 'xbmc')
  return org.jenkinsci.plugins.github_branch_source.Connector.connect('https://api.github.com', credentials)
}

@NonCPS
Long publishCheck(Run build, Long id, String name, String sha, String status, String conclusion, String title,
    String url, String summary, List annotations, String externalId) {
  def github = appGitHub(build)
  try {
    GHRepository repo = github.getRepository('xbmc/xbmc')
    def builder = id == null ? repo.createCheckRun(name, sha) : repo.updateCheckRun(id)
    builder.withStatus(GHCheckRun.Status.values().find { it.toString() == status.toLowerCase() })
    builder.withDetailsURL(url)
    builder.withExternalID(externalId)
    if (conclusion) {
      String wanted = conclusion == 'CANCELED' ? 'cancelled' : conclusion.toLowerCase()
      builder.withConclusion(GHCheckRun.Conclusion.values().find { it.toString() == wanted })
      builder.withCompletedAt(new Date())
    }
    def output = new GHCheckRunBuilder.Output(title.take(255), summary ?: title)
    // GitHub takes at most 50 annotations per request
    annotations.take(50).each { a ->
      def annotation = new GHCheckRunBuilder.Annotation(a.path, a.line as int,
          GHCheckRun.AnnotationLevel.valueOf(a.level), (a.message ?: a.level).take(4000))
      if (a.title) {
        annotation.withTitle(a.title.take(255))
      }
      output.add(annotation)
    }
    builder.add(output)
    return builder.create().id
  } finally {
    org.jenkinsci.plugins.github_branch_source.Connector.release(github)
  }
}

/** The latest check run of each name on a commit, as a build result, or RUNNING while unfinished. */
@NonCPS
Map latestStates(Run build, String sha, List names) {
  def github = appGitHub(build)
  try {
    Map latest = [:]
    github.getRepository('xbmc/xbmc').getCheckRuns(sha).toList().each { cr ->
      if (names.contains(cr.name) && (latest[cr.name] == null || latest[cr.name].id < cr.id)) {
        latest[cr.name] = [id: cr.id, run: cr]
      }
    }
    Map states = [:]
    latest.each { name, v ->
      GHCheckRun cr = v.run
      String state
      // Status and Conclusion print in lower case
      if (cr.status?.toString()?.toUpperCase() != 'COMPLETED') {
        state = 'RUNNING'
      } else {
        switch (cr.conclusion?.toString()?.toUpperCase()) {
          case 'SUCCESS':
          case 'NEUTRAL':
            state = 'SUCCESS'
            break
          case 'SKIPPED':
            state = 'SKIPPED'
            break
          case 'CANCELLED':
            state = 'ABORTED'
            break
          default:
            state = 'FAILURE'
        }
      }
      states[name] = state
    }
    return states
  } finally {
    org.jenkinsci.plugins.github_branch_source.Connector.release(github)
  }
}

@NonCPS
String botToken() {
  def cred = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
      com.cloudbees.plugins.credentials.Credentials.class).find { it.id == GITHUB_TOKEN_ID }
  if (!cred) {
    throw new IllegalStateException("credential ${GITHUB_TOKEN_ID} not found")
  }
  return cred.secret.toString()
}

@NonCPS
GHRepository repo() {
  return new GitHubBuilder().withOAuthToken(botToken()).build().getRepository('xbmc/xbmc')
}

@NonCPS
Map pullRequest(int prId) {
  GHRepository repo = repo()
  GHPullRequest pr = repo.getPullRequest(prId)
  String head = pr.head.sha
  return [
    number: prId,
    head: head,
    author: repo.getCommit(head).commitShortInfo.author.name,
    title: pr.title ?: '',
    body: pr.body ?: '',
    base: pr.base.ref,
    baseSha: pr.base.sha,
    login: pr.user.login,
    labels: pr.labels*.name,
    closed: pr.state == org.kohsuke.github.GHIssueState.CLOSED,
    merged: pr.isMerged(),
  ]
}

@NonCPS
List prFiles(int prId) {
  return repo().getPullRequest(prId).listFiles().toList()*.filename
}

/** BuildMulti-PR's pre-SCM checks: merge conflicts, merge commits and the Rebase needed label. */
@NonCPS
String preCheckFailure(int prId) {
  GHRepository repo = repo()
  GHPullRequest pr = repo.getPullRequest(prId)
  if (!pr.isMerged() && pr.getMergeable() == null) {
    Thread.sleep(10000)
    pr = repo.getPullRequest(prId)
  }

  List mergeCommits = pr.listCommits().toList().findAll { it.getParents().length >= 2 }
  String mergeCommitComment = '@' + pr.getUser().getLogin() +
      ' this contains merge commits, which Jenkins does not build. Please rebase onto the target branch instead of merging it in.'
  List posted = pr.listComments().toList().findAll {
    it.getUser().getLogin() == BOT_LOGIN && it.getBody() == mergeCommitComment
  }
  if (mergeCommits.isEmpty()) {
    posted.each { it.delete() }
  } else if (posted.isEmpty()) {
    pr.comment(mergeCommitComment)
  }

  boolean conflicted = !pr.isMerged() && (pr.getMergeable() == null || !pr.getMergeable())
  if (!mergeCommits.isEmpty()) {
    return "This PR contains ${mergeCommits.size()} merge commit(s)"
  }
  if (conflicted) {
    return 'This PR has merge conflicts'
  }
  if (!pr.isMerged() && pr.getLabels().any { it.getName() == 'Rebase needed' }) {
    String rebaseComment = '@' + pr.getUser().getLogin() + ' this needs a rebase'
    pr.listComments().toList().findAll { it.getUser().getLogin() == BOT_LOGIN && it.getBody() == rebaseComment }
        .each { it.delete() }
    pr.setLabels(*pr.getLabels().collect { it.getName() }.minus('Rebase needed'))
  }
  return null
}

/**
 * Queues a platform build as MultiJob does: declared defaults, safe overrides, pinned commit. It
 * keeps the build off the agents to avoid while another online agent can take it, dropping the
 * long-term avoided ones first.
 */
@NonCPS
Long schedule(String jobName, Map params, String pin, Run upstream, List avoid, List avoided) {
  Job job = Jenkins.get().getItemByFullName(jobName, Job)
  if (job == null) {
    throw new IllegalStateException("job ${jobName} not found")
  }
  Map values = [:]
  ParametersDefinitionProperty definitions = job.getProperty(ParametersDefinitionProperty)
  definitions?.parameterDefinitions?.each { d ->
    def value = d.defaultParameterValue
    if (value != null) {
      values[d.name] = value
    }
  }
  params.each { k, v -> values[k] = new StringParameterValue(k, v.toString()) }

  List<Action> actions = [
    new ParametersAction(new ArrayList(values.values()), params.keySet()),
    new CauseAction(new Cause.UpstreamCause(upstream)),
  ]
  if (pin) {
    actions.add(new RevisionParameterAction(pin))
  }
  Label label = labelAvoiding(job, (avoid + avoided).unique()) ?: labelAvoiding(job, avoid)
  if (label != null) {
    actions.add(new ExcludeAgents(label.getExpression()))
  }
  def result = Queue.getInstance().schedule2((Queue.Task) job, 0, actions)
  if (result.item == null) {
    throw new IllegalStateException("${jobName} was refused by the queue")
  }
  return result.item.id
}

@NonCPS
Map queueState(Long itemId) {
  def item = Queue.getInstance().getItem(itemId)
  if (item == null) {
    return [state: 'LOST']
  }
  if (item instanceof Queue.LeftItem) {
    if (item.isCancelled()) {
      return [state: 'CANCELLED']
    }
    Run run = item.getExecutable() as Run
    if (run == null) {
      return [state: 'QUEUED']
    }
    return [state: 'STARTED', id: run.externalizableId, url: run.absoluteUrl, name: run.fullDisplayName,
            waited: Math.max(0L, run.startTimeInMillis - item.getInQueueSince())]
  }
  return [state: 'QUEUED']
}

@NonCPS
Map runState(String runId) {
  Run run = Run.fromExternalizableId(runId)
  boolean building = run.isBuilding()
  if (!building) {
    // The queue copies its actions onto the build; the build record must not keep a library class
    List exclusions = run.getActions().findAll { it instanceof ExcludeAgents }
    if (!exclusions.isEmpty()) {
      exclusions.each { run.removeAction(it) }
      run.save()
    }
  }
  return [building: building, result: run.result?.toString() ?: 'FAILURE', url: run.absoluteUrl,
          name: run.fullDisplayName, duration: run.duration,
          agent: run instanceof AbstractBuild ? (((AbstractBuild) run).getBuiltOnStr() ?: null) : null]
}

/** The PR's current test merge commit. */
@NonCPS
String mergeCommit(int prId) {
  return repo().getPullRequest(prId).getMergeCommitSha()
}

@NonCPS
void stop(Long itemId, String runId) {
  if (runId) {
    Run run = Run.fromExternalizableId(runId)
    if (run?.isBuilding()) {
      def executor = run.getExecutor()
      if (executor) {
        executor.interrupt(Result.ABORTED)
      } else if (run.respondsTo('doStop')) {
        run.doStop()
      }
    }
  } else if (itemId != null) {
    def item = Queue.getInstance().getItem(itemId)
    if (item != null && !(item instanceof Queue.LeftItem)) {
      Queue.getInstance().cancel(item)
    }
  }
}

/////////////////////////////
// Review comments

/** BuildMulti-PR's post-build review: the log-format review and the style comment. */
@NonCPS
List<String> reviewComments(int prId, Run build, String formatDiff) {
  List<String> errors = []
  GHPullRequest pr = repo().getPullRequest(prId)
  try {
    checkLogging(pr)
  } catch (Exception ex) {
    errors.add('CheckLogging failed: ' + ex.toString())
  }
  try {
    errors.addAll(checkStyle(pr, build, formatDiff))
  } catch (Exception ex) {
    errors.add('CheckStyle failed: ' + ex.toString())
  }
  return errors
}

@NonCPS
void checkLogging(GHPullRequest pullRequest) {
  String commentStr = "Please update to the current log standards using `{}`.\n\nFor more information please see the [libfmt documentation](https://fmt.dev/latest/syntax.html)"
  pullRequest.listReviewComments().toList().findAll { it.getUser().getLogin() == BOT_LOGIN && it.getBody() == commentStr }
      .each { it.delete() }

  pullRequest.listReviews().toList().findAll { it.getUser().getLogin() == BOT_LOGIN }.each { review ->
    if (review.getState() == GHPullRequestReviewState.PENDING) {
      review.delete()
    } else if (review.getBody().isEmpty()) {
      review.dismiss('')
    }
  }

  Pattern printfConversion = Pattern.compile('"[^"]*%[-+#0]*(?:\\d+|\\*)?(?:\\.(?:\\d+|\\*))?(?:hh|h|ll|l|z|j|t|L)?[diouxXeEfgGcsp]')
  GHPullRequestReviewBuilder b = pullRequest.createReview()
  for (file in pullRequest.listFiles()) {
    String patch = file.getPatch()
    if (patch == null) {
      continue
    }
    boolean inLogCall = false
    patch.split('\n').eachWithIndex { line, position ->
      if (line.startsWith('-')) {
        return
      }
      if (line.contains('CLog::Log')) {
        inLogCall = true
      }
      if (inLogCall && line.startsWith('+') && printfConversion.matcher(line).find()) {
        b.comment(commentStr, file.getFilename(), position)
      }
      if (line.contains(';') || line.startsWith('@@')) {
        inLogCall = false
      }
    }
  }
  def review = b.create()
  if (review.listReviewComments().toList().size() > 0) {
    review.submit('', GHPullRequestReviewEvent.COMMENT)
  } else {
    review.delete()
  }
}

@Field final String SUGGESTION_FENCE = '```suggestion'
@Field final String SUGGESTION_REVIEW = 'clang-format would format these lines this PR changed as suggested.'

/**
 * The clang-format fixes on lines this PR changed, as suggested changes the author can apply, and
 * the comment linking the diff files for whatever could not be suggested.
 */
@NonCPS
List<String> checkStyle(GHPullRequest pullRequest, Run build, String formatDiff) {
  List<String> notes = []
  String commentStr = "I've made some formatting changes to meet the current code style. The diffs are available in the following links:"
  pullRequest.listComments().toList().findAll { it.getUser().getLogin() == BOT_LOGIN && it.getBody().startsWith(commentStr) }
      .each { it.delete() }

  List candidates = formatSuggestions(formatDiff ?: '').findAll { !it.path.endsWith('.json') }
  Map commentable = commentableLines(pullRequest)
  List wanted = candidates.findAll { s -> fits(s, commentable[s.path]) }.take(MAX_SUGGESTIONS)
  Map bodies = [:]
  wanted.each { s -> bodies["${s.path}:${s.end}:${suggestionBody(s)}".toString()] = s }

  // Keep the suggestions still wanted, so a rebuild does not repost them
  pullRequest.listReviewComments().toList().findAll {
    it.getUser().getLogin() == BOT_LOGIN && (it.getBody() ?: '').startsWith(SUGGESTION_FENCE)
  }.each { c ->
    Integer line = null
    try {
      line = c.getLine() as Integer
    } catch (Exception ignored) {
      // An outdated comment has no line
    }
    String key = "${c.getPath()}:${line}:${c.getBody()}".toString()
    if (line != null && line > 0 && bodies.containsKey(key)) {
      bodies.remove(key)
    } else {
      c.delete()
    }
  }
  boolean suggested = true
  if (!bodies.isEmpty()) {
    try {
      postSuggestions(pullRequest.getNumber(), HEAD_SHA, bodies.values() as List)
    } catch (Exception e) {
      suggested = false
      notes.add("Could not post the format suggestions: ${e}".toString())
    }
  }

  def artifacts = build.getArtifacts()
  if (artifacts.size() > 0 && !(suggested && wanted.size() == candidates.size())) {
    String url = build.getAbsoluteUrl() + 'artifact/'
    for (artifact in artifacts) {
      String displayName = artifact.getDisplayPath()
      commentStr = commentStr + "\n - [${displayName}](${url + displayName})"
    }
    commentStr = commentStr + "\n\nFor more information please see our [current code style guidelines](https://github.com/xbmc/xbmc/blob/master/docs/CODE_GUIDELINES.md)."
    pullRequest.comment(commentStr)
  }
  return notes
}

/**
 * The changes in a `git clang-format --diff` as replacements of whole lines of the original file:
 * [path, start, end, lines]. An insertion replaces the context line above it with itself plus the
 * inserted lines.
 */
@NonCPS
List formatSuggestions(String diff) {
  List out = []
  String file = null
  int oldLine = 0
  Map run = null
  String context = null
  int contextLine = 0
  Closure flush = {
    if (run != null && file != null) {
      if (!run.removed.isEmpty()) {
        out.add([path: file, start: run.start, end: run.start + run.removed.size() - 1, lines: run.added])
      } else if (context != null && contextLine == run.start - 1) {
        out.add([path: file, start: contextLine, end: contextLine, lines: [context] + run.added])
      }
    }
    run = null
  }
  diff.eachLine { line ->
    if (line.startsWith('diff --git ')) {
      flush()
      file = null
      context = null
      return
    }
    if (line.startsWith('--- ')) {
      flush()
      context = null
      return
    }
    if (line.startsWith('+++ ')) {
      file = line == '+++ /dev/null' ? null : line.substring(4).replaceFirst('^b/', '')
      return
    }
    Matcher hunk = line =~ /^@@ -(\d+)(?:,\d+)? \+\d+(?:,\d+)? @@/
    if (hunk.find()) {
      flush()
      context = null
      oldLine = hunk.group(1) as int
      return
    }
    if (file == null || line.startsWith('\\')) {
      return
    }
    if (line.startsWith('-')) {
      run = run ?: [start: oldLine, removed: [], added: []]
      run.removed.add(line.substring(1))
      oldLine++
    } else if (line.startsWith('+')) {
      run = run ?: [start: oldLine, removed: [], added: []]
      run.added.add(line.substring(1))
    } else {
      flush()
      context = line.isEmpty() ? '' : line.substring(1)
      contextLine = oldLine
      oldLine++
    }
  }
  flush()
  return out
}

/** Each file's diff hunks on the PR head's side: [first, last, lines the PR added]. */
@NonCPS
Map commentableLines(GHPullRequest pullRequest) {
  Map out = [:]
  for (file in pullRequest.listFiles()) {
    String patch = file.getPatch()
    if (patch == null) {
      continue
    }
    List hunks = []
    Map hunk = null
    int right = 0
    patch.split('\n').each { line ->
      Matcher m = line =~ /^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/
      if (m.find()) {
        int start = m.group(1) as int
        int count = m.group(2) != null ? m.group(2) as int : 1
        hunk = [first: start, last: start + count - 1, added: new HashSet()]
        hunks.add(hunk)
        right = start
        return
      }
      if (hunk == null || line.startsWith('-') || line.startsWith('\\')) {
        return
      }
      if (line.startsWith('+')) {
        hunk.added.add(right)
      }
      right++
    }
    out[file.getFilename()] = hunks
  }
  return out
}

@NonCPS
boolean fits(Map s, List hunks) {
  return (hunks ?: []).any { h ->
    s.start >= h.first && s.end <= h.last && (s.start..s.end).any { h.added.contains(it) }
  }
}

@NonCPS
String suggestionBody(Map s) {
  return SUGGESTION_FENCE + '\n' + s.lines.collect { it + '\n' }.join('') + '```'
}

/** One review holding a suggested change per formatting fix, through the REST API. */
@NonCPS
void postSuggestions(int number, String sha, List suggestions) {
  List comments = suggestions.collect { s ->
    Map c = [path: s.path, line: s.end, side: 'RIGHT', body: suggestionBody(s)]
    if (s.start < s.end) {
      c.start_line = s.start
      c.start_side = 'RIGHT'
    }
    return c
  }
  String payload = JsonOutput.toJson([commit_id: sha, event: 'COMMENT', body: SUGGESTION_REVIEW, comments: comments])
  HttpURLConnection conn = (HttpURLConnection) new URL("https://api.github.com/repos/xbmc/xbmc/pulls/${number}/reviews").openConnection()
  conn.setRequestMethod('POST')
  conn.setRequestProperty('Authorization', "Bearer ${botToken()}".toString())
  conn.setRequestProperty('Accept', 'application/vnd.github+json')
  conn.setRequestProperty('X-GitHub-Api-Version', '2022-11-28')
  conn.setRequestProperty('Content-Type', 'application/json; charset=utf-8')
  conn.setDoOutput(true)
  conn.getOutputStream().withWriter('UTF-8') { it << payload }
  int code = conn.getResponseCode()
  if (code >= 300) {
    String body = conn.getErrorStream()?.getText('UTF-8') ?: ''
    throw new IllegalStateException("HTTP ${code}: ${body.take(500)}")
  }
}
