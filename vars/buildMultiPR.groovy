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
  recordRevision(currentBuild.rawBuild, pr.head, pr.baseSha)

  List platforms = platformsFor(pr.base)
  for (Map p : platforms) {
    publishChecks name: p.check, status: 'QUEUED', title: 'Waiting for the PR checks', detailsURL: env.BUILD_URL
  }
  publishChecks name: SUMMARY_CHECK, status: 'IN_PROGRESS', title: 'Checking the pull request',
      detailsURL: env.BUILD_URL

  Map results = [:]
  String stopped = null
  try {
    String mergeSha = null
    node('buildall') {
      ws('workspace/BuildMulti-PR-checks') {
        stopped = preCheckFailure(prId)
        if (!stopped) {
          mergeSha = checkoutAndFormat(prId)
        }
      }
    }
    if (stopped) {
      echo "ERROR: ${stopped}"
      return
    }

    Map ctx = [prId: prId, pr: pr, mergeSha: mergeSha]
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
        publishChecks name: p.check, status: 'COMPLETED', conclusion: stopped ? 'CANCELED' : 'SKIPPED',
            title: stopped ?: 'Not built', detailsURL: env.BUILD_URL
      }
    }
    String overall = stopped ? 'ABORTED' : worst(results.values())
    if (!stopped) {
      reviewComments(prId, currentBuild.rawBuild).each { echo it }
    }
    publishChecks name: SUMMARY_CHECK, status: 'COMPLETED',
        conclusion: stopped && stopped != 'Cancelled' ? 'FAILURE' : conclusionOf(overall),
        title: stopped ?: summaryTitle(results), detailsURL: env.BUILD_URL
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
        publishChecks name: p.check, status: 'COMPLETED', conclusion: 'CANCELED', title: 'Removed from the queue',
            detailsURL: env.BUILD_URL
        return 'ABORTED'
      }
      runId = s.id
      publishChecks name: p.check, status: 'IN_PROGRESS', title: s.name, detailsURL: s.url
      waitUntil(initialRecurrencePeriod: 15000, quiet: true) {
        s = runState(runId)
        return !s.building
      }
      if (s.result != 'SUCCESS' && attempt < retries && isRetryable(runId)) {
        attempt++
        publishChecks name: p.check, status: 'IN_PROGRESS', title: "${s.name}: ${s.result}, retrying",
            detailsURL: s.url
        continue
      }
      publishChecks name: p.check, status: 'COMPLETED', conclusion: conclusionOf(s.result), title: "${s.name}: ${s.result}",
          detailsURL: s.url
      return s.result
    }
  } catch (FlowInterruptedException e) {
    stop(itemId, runId)
    publishChecks name: p.check, status: 'COMPLETED', conclusion: 'CANCELED', title: 'Cancelled',
        detailsURL: env.BUILD_URL
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

/////////////////////////////
// Jenkins and GitHub API calls, outside CPS

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

/**
 * Records the PR's revision on the build, so publishChecks finds the commit without fetching
 * the PR again; a Jenkinsfile from the Remote Jenkinsfile Provider leaves none.
 */
@NonCPS
void recordRevision(Run build, String headSha, String baseSha) {
  def source = jenkins.scm.api.SCMSource.SourceByItem.findSource(build.parent)
  def head = jenkins.scm.api.SCMHead.HeadByItem.findHead(build.parent)
  def revision = new org.jenkinsci.plugins.github_branch_source.PullRequestSCMRevision(head, baseSha, headSha)
  build.addAction(new jenkins.scm.api.SCMRevisionAction(source, revision))
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
    if (review.getBody().isEmpty()) {
      review.dismiss('')
    }
    if (review.getState() == GHPullRequestReviewState.PENDING) {
      review.delete()
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
