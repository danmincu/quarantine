package org.jenkinsci.plugins.quarantine;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.model.Result;
import hudson.tasks.junit.JUnitParser;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.junit.TestResultAction.Data;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.JUnitResultArchiver;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.apache.commons.io.IOUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class QuarantinableJUnitResultArchiver extends JUnitResultArchiver {

	@DataBoundConstructor
	public QuarantinableJUnitResultArchiver(String testResults) {
		super(testResults);
	}

	/**
	 * Because build results can only be made worse, we can't just run another
	 * recorder straight after the JUnitResultArchiver. So we clone-and-own the
	 * {@link JUnitResultArchiver#perform(AbstractBuild, Launcher, BuildListener)}
	 * method here so we can inspect the quarantine before making the PASS/FAIL
	 * decision
	 *
	 * The build is only failed if there are failing tests that have not been put
	 * in quarantine
	 */
	@Override
	public void perform(Run build, FilePath workspace, Launcher launcher,
			TaskListener listener) throws InterruptedException, IOException {

		listener.getLogger().println("JUnitResultArchiver.Recording");

		final String testResults = build.getEnvironment(listener).expand(getTestResults());

		// ideally, we'd use parse() here, but it's been made private... :-(
		TestResult result = new JUnitParser(isKeepLongStdio()).parseResult(testResults, build, workspace, launcher, listener);

		synchronized (build) {
			TestResultAction action = build.getAction(TestResultAction.class);
			try {
				action = new TestResultAction(build, result, listener);
			} catch (NullPointerException npe) {
				throw new AbortException(Messages.QuarantinableJUnitResultArchiver_BadXML(testResults));
			}
			result.freeze(action);
			action.setHealthScaleFactor(getHealthScaleFactor()); // overwrites previous value if appending
			if (result.isEmpty()) {
				if (build.getResult() == Result.FAILURE) {
					// most likely a build failed before it gets to the test phase.
					// don't report confusing error message.
					return;
				}
				// most likely a configuration error in the job - e.g. false pattern to match the JUnit result files
				throw new AbortException("JUnitResultArchiver.ResultIsEmpty");
			}

			// TODO: Move into JUnitParser [BUG 3123310]
			// FIXME: ideally, we'd use action.getData() so we can add to the data, but it's not accessible.
			//   create a new list of data - not sure what the implications are, but that's how the quarantine
			//   plugin worked before
			List<Data> data = new ArrayList<Data>();
			if (getTestDataPublishers() != null) {
				for (TestDataPublisher tdp : getTestDataPublishers()) {
					Data d = tdp.contributeTestData(build, workspace, launcher, listener, result);
					if (d != null) {
						data.add(d);
					}
				}
				action.setData(data);
			}



			listener.getLogger().println("Search workspace:" + workspace + " for quarantined-tests.json files.");

			List<TestSetting> listOfQuaratinedTests = new ArrayList<>();

			try
			{
			if(!workspace.isRemote())
			{
				Collection<File> files = listFileTree(new File(workspace.getRemote()));

				listener.getLogger().println("Quarantined tests from `quarantined-tests.json` files.");
				for ( File f: files) {
					if (f.getName().equals("quarantined-tests.json"))
					{
						InputStream is = new FileInputStream(f.getAbsoluteFile());
						String jsonTxt = IOUtils.toString(is, "UTF-8");
						// System.out.println(jsonTxt);
						JSONArray testArray = (JSONArray) JSONSerializer.toJSON(jsonTxt);
						List<TestSetting> listOfTests = TestSetting.fillList(testArray);

						for (TestSetting testSetting: listOfTests) {
							listener.getLogger().println("Test: " + testSetting.name + ". Reason: " + testSetting.reason);
							listOfQuaratinedTests.add(testSetting);
						}
					}
				}
			}}
			// catch and bury exceptions while loading the quarantined-tests.json
			catch (Exception ex)
			{
				listener.getLogger().println("EXCEPTION while loading the `quarantined-tests.json` files:" + ex);
			}


			build.addAction(action);

			if (action.getResult().getFailCount() > 0)
			{
				int quarantined = 0;
				for (CaseResult case_result : action.getResult().getFailedTests()) {

					TestSetting matchingTest = null;
					for (TestSetting ts: listOfQuaratinedTests) {

						if (ts.name.equals(case_result.getFullName()))
						{
							matchingTest = ts;
						}
					}
					// unsure if Java 8 is available on all jenkins servers?!
					// TestSetting matchingTest = listOfQuaratinedTests.stream().filter((test) -> test.name.equals(case_result.getFullName())).findFirst().orElse(null);
					if (matchingTest != null)
					{
						listener.getLogger().println("[Quarantine]: " + case_result.getFullName() + " failed but is quarantined in `quarantined-tests.json`.");
						quarantined++;
					}
					else
					{
						QuarantineTestAction quarantineAction = case_result.getTestAction(QuarantineTestAction.class);
						if (quarantineAction != null) {
							if (quarantineAction.isQuarantined()) {
								listener.getLogger().println("[Quarantine]: " + case_result.getFullName() + " failed but is quarantined");
								quarantined++;
							}
						}
					}
				}

				int remaining = action.getResult().getFailCount() - quarantined;
				listener.getLogger().println("[Quarantine]: " + remaining + " unquarantined failures remaining");

				if (remaining > 0)
					build.setResult(Result.UNSTABLE);
			}
		}
	}


	public static Collection<File> listFileTree(File dir) {
		Set<File> fileTree = new HashSet<File>();
		if(dir==null||dir.listFiles()==null){
			return fileTree;
		}
		for (File entry : dir.listFiles()) {
			if (entry.isFile()) fileTree.add(entry);
			else fileTree.addAll(listFileTree(entry));
		}
		return fileTree;
	}


	@Extension
	public static class DescriptorImpl extends JUnitResultArchiver.DescriptorImpl {
		public String getDisplayName() {
			return Messages.QuarantinableJUnitResultArchiver_DisplayName();
		}
	}


	public static class TestSetting {
		private String name;

		private String reason;

		public void setName(String name){
			this.name = name;
		}
		public String getName(){
			return this.name;
		}
		public void setReason(String reason){
			this.reason = reason;
		}
		public String getReason(){
			return this.reason;
		}

		public static TestSetting fill(JSONObject jo){
			TestSetting o = new TestSetting();
			if (jo.containsKey("name")) {
				o.setName(jo.getString("name"));
			}
			if (jo.containsKey("reason")) {
				o.setReason(jo.getString("reason"));
			}
			return o;
		}

		public static List<TestSetting> fillList(JSONArray ja) {
			if (ja == null || ja.size() == 0)
				return null;
			List<TestSetting> sqs = new ArrayList<TestSetting>();
			for (int i = 0; i < ja.size(); i++) {
				sqs.add(fill(ja.getJSONObject(i)));
			}
			return sqs;
		}

	}

}
