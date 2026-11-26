/*
 * Builds an xbmc/xbmc pull request on every platform. Each platform job starts as soon as its own
 * agent is free and reports as its own GitHub check; there is no shared one-PR-at-a-time slot.
 *
 * The platform jobs are started the way BuildMulti-PR's MultiJob phase starts them: the same
 * parameters, marked safe so the freestyle jobs see the ghprb* values, and the GitHub merge commit
 * pinned with a RevisionParameterAction.
 *
 * Pilot: builds only pull requests whose head commit is authored by PILOT_AUTHOR, the same rule
 * BuildMulti-PR's blackListCommitAuthor uses to skip them.
 */

import groovy.transform.Field
import hudson.model.Action
import hudson.model.Cause
import hudson.model.CauseAction
import hudson.model.Job
import hudson.model.ParametersAction
import hudson.model.ParametersDefinitionProperty
import hudson.model.Queue
import hudson.model.Result
import hudson.model.Run
import hudson.model.StringParameterValue
import hudson.plugins.git.RevisionParameterAction
import java.util.regex.Pattern
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
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
  [check: 'android-arm-docker', job: 'android-arm-docker'],
  [check: 'android-arm64-docker', job: 'android-arm64-docker'],
  [check: 'android-x86-docker', job: 'android-x86-docker', bare: true],
  [check: 'LINUX-64-GL-docker', job: 'linux-docker',
   params: [RENDER_SYSTEM: 'gl', PLATFORM: 'Linux_x86_64', RUN_TESTS: 'true', FILTER_TESTS: '-TestNetwork.PingHost']],
  [check: 'LINUX-64-GLES-docker', job: 'linux-docker',
   params: [RENDER_SYSTEM: 'gles', PLATFORM: 'Linux_x86_64', RUN_TESTS: 'true', FILTER_TESTS: '-TestNetwork.PingHost']],
  [check: 'LINUX-arm-GLES', job: 'LINUX',
   params: [RENDER_SYSTEM: 'gles', BUILD_HOST: 'arm-linux-gnueabihf', CORE_PLATFORM_NAME: 'wayland gbm']],
  [check: 'LINUX-aarch64-GLES-docker', job: 'linux-docker', params: [RENDER_SYSTEM: 'gles', PLATFORM: 'Linux_arm64']],
  [check: 'IOS-ARM64', job: 'IOS-ARM64', retries: 2],
  [check: 'TVOS', job: 'TVOS', retries: 2],
  [check: 'OSX-64', job: 'OSX-64', retries: 2],
  [check: 'OSX-ARM64', job: 'OSX-ARM64', retries: 2],
  [check: 'WIN-32', job: 'WIN-32', notOn: 'master'],
  [check: 'WIN-64', job: 'WIN-64'],
  [check: 'WIN-ARM64', job: 'WIN-ARM64', onlyOn: 'master'],
  [check: 'WIN-UWP-64', job: 'WIN-UWP-64'],
  [check: 'webos-docker', job: 'webos-docker', noRevision: true, params: [qualityGateThreshold: '2']],
]

def call() {
  properties([
    disableConcurrentBuilds(abortPrevious: true),
    buildDiscarder(logRotator(numToKeepStr: '30')),
  ])

  int prId = env.CHANGE_ID as int
  Map pr = pullRequest(prId)
  String skip = skipReason(prId, pr)
  if (skip) {
    currentBuild.result = 'NOT_BUILT'
    currentBuild.description = skip
    return
  }
  currentBuild.description = pr.title
  HEAD_SHA = pr.head

  List platforms = platformsFor(pr.base)
  for (Map p : platforms) {
    check(p.check, 'QUEUED', null, 'Waiting for the PR checks', env.BUILD_URL)
  }
  check(SUMMARY_CHECK, 'IN_PROGRESS', null, 'Checking the pull request', env.BUILD_URL)

  Map results = [:]
  String stopped = null
  try {
    String mergeSha = null
    Map changed = [:]
    node('buildall') {
      ws('workspace/BuildMulti-PR-checks') {
        stopped = preCheckFailure(prId)
        if (!stopped) {
          mergeSha = checkoutAndFormat(prId)
          changed = changedLines(sh(returnStdout: true, script: 'git diff -U0 --no-color --no-ext-diff HEAD~ HEAD'))
        }
      }
    }
    if (stopped) {
      echo "ERROR: ${stopped}"
      return
    }

    Map ctx = [prId: prId, pr: pr, mergeSha: mergeSha, changed: changed]
    Map branches = [:]
    for (Map p : platforms) {
      Map platform = p
      branches[platform.check] = { results[platform.check] = runPlatform(platform, ctx) }
    }
    parallel branches
  } catch (FlowInterruptedException e) {
    stopped = 'Cancelled'
    throw e
  } finally {
    for (Map p : platforms) {
      if (!results.containsKey(p.check)) {
        check(p.check, 'COMPLETED', stopped ? 'CANCELED' : 'SKIPPED', stopped ?: 'Not built', env.BUILD_URL)
      }
    }
    String overall = stopped ? 'ABORTED' : worst(results.values())
    if (!stopped) {
      reviewComments(prId, currentBuild.rawBuild).each { echo it }
    }
    check(SUMMARY_CHECK, 'COMPLETED', stopped && stopped != 'Cancelled' ? 'FAILURE' : conclusionOf(overall), stopped ?: summaryTitle(results), env.BUILD_URL)
    if (overall != 'SUCCESS') {
      currentBuild.result = overall
    }
    if (!stopped) {
      notifySlack(overall, pr.title)
    }
  }
}

/** One platform: start its job, follow it, retry it on a known infrastructure failure. */
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
  try {
    int attempt = 0
    while (true) {
      itemId = schedule(p.job, params, pin, currentBuild.rawBuild)
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
      check(p.check, 'IN_PROGRESS', null, s.name, s.url)
      waitUntil(initialRecurrencePeriod: 15000, quiet: true) {
        s = runState(runId)
        return !s.building
      }
      // GitHub replaces the merge commit whenever the target moves, so a build that queued long
      // enough cannot fetch the one it was pinned to
      if (s.result != 'SUCCESS' && pin && attempt < retries && lostPin(runId, pin)) {
        attempt++
        pin = mergeCommit(ctx.prId) ?: pin
        check(p.check, 'IN_PROGRESS', null, "${s.name}: merge commit replaced, retrying", s.url)
        continue
      }
      if (s.result != 'SUCCESS' && attempt < retries && isRetryable(runId)) {
        attempt++
        check(p.check, 'IN_PROGRESS', null, "${s.name}: ${s.result}, retrying", s.url)
        continue
      }
      List warnings = []
      try {
        warnings = newWarnings(issuesOf(runId), ctx.changed)
      } catch (Exception e) {
        echo "Could not read the warnings of ${s.name}: ${e}"
      }
      check(p.check, 'COMPLETED', conclusionOf(s.result), warningsTitle("${s.name}: ${s.result}", warnings), s.url,
          warningsSummary(warnings), warnings)
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
String skipReason(int prId, Map pr) {
  if (!pr.author.equalsIgnoreCase(PILOT_AUTHOR)) {
    return "Not in the pilot (head commit by ${pr.author})"
  }
  if (pr.closed) {
    return "PR #${prId} is ${pr.merged ? 'merged' : 'closed'}, nothing to build"
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
  List files = repo().getPullRequest(prId).listFiles().toList()*.filename
  if (!files.isEmpty() && files.every { f -> EXCLUDED_PATHS.any { f ==~ it } }) {
    return 'Only excluded paths changed'
  }
  return null
}

String checkoutAndFormat(int prId) {
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
  return sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
}

@NonCPS
String worst(Collection results) {
  if (results.isEmpty() || results.contains(null) || results.contains('ABORTED')) {
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
    List warnings) {
  try {
    CHECK_RUNS[name] = publishCheck(currentBuild.rawBuild, CHECK_RUNS[name] as Long, name, HEAD_SHA, status,
        conclusion, title, url, summary, warnings)
  } catch (Exception e) {
    echo "Could not publish check ${name}: ${e}"
    if (!warnings.isEmpty()) {
      // A rejected annotation must not leave the check unfinished
      check(name, status, conclusion, title, url, summary, [])
    }
  }
}

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

@NonCPS
String warningsTitle(String title, List warnings) {
  if (warnings.isEmpty()) {
    return title
  }
  return "${title}, ${warnings.size()} new warning${warnings.size() == 1 ? '' : 's'}".toString()
}

@NonCPS
String warningsSummary(List warnings) {
  if (warnings.isEmpty()) {
    return null
  }
  String listed = warnings.take(100).collect { w -> "- `${w.path}:${w.line}` ${w.message}" }.join('\n')
  if (warnings.size() > 100) {
    listed += "\n- and ${warnings.size() - 100} more"
  }
  return 'Warnings on lines this PR changed:\n\n' + listed
}

/////////////////////////////
// Jenkins and GitHub API calls, outside CPS

@NonCPS
Long publishCheck(Run build, Long id, String name, String sha, String status, String conclusion, String title,
    String url, String summary, List warnings) {
  def credentials = org.jenkinsci.plugins.github_branch_source.Connector.lookupScanCredentials(
      build.parent, 'https://api.github.com', GITHUB_APP_ID, 'xbmc')
  def github = org.jenkinsci.plugins.github_branch_source.Connector.connect('https://api.github.com', credentials)
  try {
    GHRepository repo = github.getRepository('xbmc/xbmc')
    def builder = id == null ? repo.createCheckRun(name, sha) : repo.updateCheckRun(id)
    builder.withStatus(GHCheckRun.Status.values().find { it.toString() == status.toLowerCase() })
    builder.withDetailsURL(url)
    if (conclusion) {
      String wanted = conclusion == 'CANCELED' ? 'cancelled' : conclusion.toLowerCase()
      builder.withConclusion(GHCheckRun.Conclusion.values().find { it.toString() == wanted })
      builder.withCompletedAt(new Date())
    }
    def output = new GHCheckRunBuilder.Output(title, summary ?: title)
    // GitHub takes at most 50 annotations per request
    warnings.take(50).each { w ->
      output.add(new GHCheckRunBuilder.Annotation(w.path, w.line as int, GHCheckRun.AnnotationLevel.WARNING,
          w.message ?: 'warning'))
    }
    builder.add(output)
    return builder.create().id
  } finally {
    org.jenkinsci.plugins.github_branch_source.Connector.release(github)
  }
}

@NonCPS
GHRepository repo() {
  def cred = com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials(
      com.cloudbees.plugins.credentials.Credentials.class).find { it.id == GITHUB_TOKEN_ID }
  if (!cred) {
    throw new IllegalStateException("credential ${GITHUB_TOKEN_ID} not found")
  }
  return new GitHubBuilder().withOAuthToken(cred.secret.toString()).build().getRepository('xbmc/xbmc')
}

@NonCPS
Map pullRequest(int prId) {
  GHRepository repo = repo()
  GHPullRequest pr = repo.getPullRequest(prId)
  String head = pr.head.sha
  return [
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

/** Queues a platform build as MultiJob does: declared defaults, safe overrides, pinned commit. */
@NonCPS
Long schedule(String jobName, Map params, String pin, Run upstream) {
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
    return [state: 'STARTED', id: run.externalizableId, url: run.absoluteUrl, name: run.fullDisplayName]
  }
  return [state: 'QUEUED']
}

@NonCPS
Map runState(String runId) {
  Run run = Run.fromExternalizableId(runId)
  return [building: run.isBuilding(), result: run.result?.toString() ?: 'FAILURE', url: run.absoluteUrl,
          name: run.fullDisplayName]
}

/** The run's checkout failed because the pinned commit could not be found. */
@NonCPS
boolean lostPin(String runId, String pin) {
  Run run = Run.fromExternalizableId(runId)
  BufferedReader reader = new BufferedReader(run.logReader)
  String failure = "rev-parse ${pin}^{commit}\" returned status code 128".toString()
  try {
    String line
    while ((line = reader.readLine()) != null) {
      if (line.contains(failure)) {
        return true
      }
    }
  } finally {
    reader.close()
  }
  return false
}

/** The PR's current test merge commit. */
@NonCPS
String mergeCommit(int prId) {
  return repo().getPullRequest(prId).getMergeCommitSha()
}

/** MultiJob's retry rule: the log matches a line of the shared failure rules. */
@NonCPS
boolean isRetryable(String runId) {
  List<Pattern> rules = new File(RETRY_RULES).readLines().findAll { it.trim() }.collect { Pattern.compile(it) }
  Run run = Run.fromExternalizableId(runId)
  BufferedReader reader = new BufferedReader(run.logReader)
  try {
    String line
    while ((line = reader.readLine()) != null) {
      for (Pattern rule : rules) {
        if (rule.matcher(line).find()) {
          return true
        }
      }
    }
  } finally {
    reader.close()
  }
  return false
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

/** BuildMulti-PR's post-build review: the log-format review and the style diff comment. */
@NonCPS
List<String> reviewComments(int prId, Run build) {
  List<String> errors = []
  GHPullRequest pr = repo().getPullRequest(prId)
  try {
    checkLogging(pr)
  } catch (Exception ex) {
    errors.add('CheckLogging failed: ' + ex.toString())
  }
  try {
    checkStyle(pr, build)
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

@NonCPS
void checkStyle(GHPullRequest pullRequest, Run build) {
  String commentStr = "I've made some formatting changes to meet the current code style. The diffs are available in the following links:"
  pullRequest.listComments().toList().findAll { it.getUser().getLogin() == BOT_LOGIN && it.getBody().startsWith(commentStr) }
      .each { it.delete() }

  def artifacts = build.getArtifacts()
  String url = build.getAbsoluteUrl() + 'artifact/'
  for (artifact in artifacts) {
    String displayName = artifact.getDisplayPath()
    commentStr = commentStr + "\n - [${displayName}](${url + displayName})"
  }
  if (artifacts.size() > 0) {
    commentStr = commentStr + "\n\nFor more information please see our [current code style guidelines](https://github.com/xbmc/xbmc/blob/master/docs/CODE_GUIDELINES.md)."
    pullRequest.comment(commentStr)
  }
}
