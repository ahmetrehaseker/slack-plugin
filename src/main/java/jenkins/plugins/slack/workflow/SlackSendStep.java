package jenkins.plugins.slack.workflow;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.HostnameRequirement;
import hudson.AbortException;
import hudson.Extension;
import hudson.Util;
import hudson.model.Project;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.plugins.slack.Messages;
import jenkins.plugins.slack.SlackNotifier;
import jenkins.plugins.slack.SlackService;
import jenkins.plugins.slack.StandardSlackService;
import net.sf.json.JSON;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import net.sf.json.groovy.JsonSlurper;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import javax.inject.Inject;


import static com.cloudbees.plugins.credentials.CredentialsProvider.lookupCredentials;

/**
 * Workflow step to send a Slack channel notification.
 */
public class SlackSendStep extends AbstractStepImpl {

    private final @Nonnull String message;
    private String color;
    private String token;
    private String tokenCredentialId;
    private boolean botUser;
    private String channel;
    private String teamDomain;
    private boolean failOnError;
    private String attachments;


    @Nonnull
    public String getMessage() {
        return message;
    }

    public String getColor() {
        return color;
    }

    @DataBoundSetter
    public void setColor(String color) {
        this.color = Util.fixEmpty(color);
    }

    public String getToken() {
        return token;
    }

    @DataBoundSetter
    public void setToken(String token) {
        this.token = Util.fixEmpty(token);
    }

    public String getTokenCredentialId() {
        return tokenCredentialId;
    }

    @DataBoundSetter
    public void setTokenCredentialId(String tokenCredentialId) {
        this.tokenCredentialId = Util.fixEmpty(tokenCredentialId);
    }

    public boolean getBotUser() {
        return botUser;
    }

    @DataBoundSetter
    public void setBotUser(boolean botUser) {
        this.botUser = botUser;
    }

    public String getChannel() {
        return channel;
    }

    @DataBoundSetter
    public void setChannel(String channel) {
        this.channel = Util.fixEmpty(channel);
    }

    public String getTeamDomain() {
        return teamDomain;
    }

    @DataBoundSetter
    public void setTeamDomain(String teamDomain) {
        this.teamDomain = Util.fixEmpty(teamDomain);
    }

    public boolean isFailOnError() {
        return failOnError;
    }

    @DataBoundSetter
    public void setFailOnError(boolean failOnError) {
        this.failOnError = failOnError;
    }

    @DataBoundSetter
    public void setAttachments(String attachments){
        this.attachments = attachments;
    }

    public String getAttachments(){
        return attachments;
    }

    @DataBoundConstructor
    public SlackSendStep(@Nonnull String message) {
        this.message = message;
    }

    @Extension
    public static class DescriptorImpl extends AbstractStepDescriptorImpl {

        public DescriptorImpl() {
            super(SlackSendStepExecution.class);
        }

        @Override
        public String getFunctionName() {
            return "slackSend";
        }

        @Override
        public String getDisplayName() {
            return Messages.SlackSendStepDisplayName();
        }

        public ListBoxModel doFillTokenCredentialIdItems(@AncestorInPath Project project) {
            if (!Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER)) {
                return new ListBoxModel();
            }
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withAll(lookupCredentials(
                            StringCredentials.class,
                            project,
                            ACL.SYSTEM,
                            new HostnameRequirement("*.slack.com"))
                    );
        }

        //WARN users that they should not use the plain/exposed token, but rather the token credential id
        public FormValidation doCheckToken(@QueryParameter String value) {
            //always show the warning - TODO investigate if there is a better way to handle this
            return FormValidation.warning("Exposing your Integration Token is a security risk. Please use the Integration Token Credential ID");
        }
    }

    public static class SlackSendStepExecution extends AbstractSynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;

        @Inject
        transient SlackSendStep step;

        @StepContextParameter
        transient TaskListener listener;

        @Override
        protected Void run() throws Exception {

            //default to global config values if not set in step, but allow step to override all global settings
            Jenkins jenkins;
            //Jenkins.getInstance() may return null, no message sent in that case
            try {
                jenkins = Jenkins.getInstance();
            } catch (NullPointerException ne) {
                listener.error(Messages.NotificationFailedWithException(ne));
                return null;
            }
            SlackNotifier.DescriptorImpl slackDesc = jenkins.getDescriptorByType(SlackNotifier.DescriptorImpl.class);
            listener.getLogger().println("run slackstepsend, step " + step.token+":" + step.botUser+", desc " + slackDesc.getToken()+":"+slackDesc.getBotUser());
            String team = step.teamDomain != null ? step.teamDomain : slackDesc.getTeamDomain();
            String tokenCredentialId = step.tokenCredentialId != null ? step.tokenCredentialId : slackDesc.getTokenCredentialId();
            String token;
            boolean botUser;
            if (step.token != null) {
                token = step.token;
                botUser = step.botUser;
            } else {
                token = slackDesc.getToken();
                botUser = slackDesc.getBotUser();
            }
            String channel = step.channel != null ? step.channel : slackDesc.getRoom();
            String color = step.color != null ? step.color : "";

            //placing in console log to simplify testing of retrieving values from global config or from step field; also used for tests
            listener.getLogger().println(Messages.SlackSendStepConfig(step.teamDomain == null, step.token == null, step.channel == null, step.color == null));

            SlackService slackService = getSlackService(team, token, tokenCredentialId, botUser, channel);
            boolean publishSuccess;
            if(step.attachments != null){
                JsonSlurper jsonSlurper = new JsonSlurper();
                JSON json = null;
                try {
                    json = jsonSlurper.parseText(step.attachments);
                } catch (JSONException e) {
                    listener.error(Messages.NotificationFailedWithException(e));
                    return null;
                }
                if(!(json instanceof JSONArray)){
                    listener.error(Messages.NotificationFailedWithException(new IllegalArgumentException("Attachments must be JSONArray")));
                    return null;
                }
                JSONArray jsonArray = (JSONArray) json;
                for (int i = 0; i < jsonArray.size(); i++) {
                    Object object = jsonArray.get(i);
                    if(object instanceof JSONObject){
                        JSONObject jsonNode = ((JSONObject) object);
                        if (!jsonNode.has("fallback")) {
                            jsonNode.put("fallback", step.message);
                        }
                    }
                }
                publishSuccess = slackService.publish(jsonArray, color);
            }else{
                publishSuccess = slackService.publish(step.message, color);
            }
            if (!publishSuccess && step.failOnError) {
                throw new AbortException(Messages.NotificationFailed());
            } else if (!publishSuccess) {
                listener.error(Messages.NotificationFailed());
            }
            return null;
        }

        //streamline unit testing
        SlackService getSlackService(String team, String token, String tokenCredentialId, boolean botUser, String channel) {
            return new StandardSlackService(team, token, tokenCredentialId, botUser, channel);
        }
    }
}