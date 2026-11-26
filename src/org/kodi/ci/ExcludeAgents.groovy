package org.kodi.ci

import com.cloudbees.groovy.cps.NonCPS
import hudson.model.InvisibleAction
import hudson.model.Label
import hudson.model.labels.LabelAssignmentAction
import hudson.model.queue.SubTask
import jenkins.model.Jenkins

/**
 * Gives a queued build a label expression in place of its job's, to keep it off some agents.
 * The queue copies it onto the build, which drops it again so the build record never names a
 * library class.
 */
class ExcludeAgents extends InvisibleAction implements LabelAssignmentAction {
  final String expression

  ExcludeAgents(String expression) {
    this.expression = expression
  }

  @NonCPS
  @Override
  Label getAssignedLabel(SubTask task) {
    return Jenkins.get().getLabel(expression)
  }
}
