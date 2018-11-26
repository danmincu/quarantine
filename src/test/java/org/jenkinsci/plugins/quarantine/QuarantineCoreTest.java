/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Tom Huybrechts
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.quarantine;

import hudson.model.FreeStyleBuild;
import hudson.model.Result;
import hudson.model.Descriptor;
import hudson.model.FreeStyleProject;
import hudson.model.User;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import hudson.tasks.Mailer;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.tasks.junit.TestResultAction;
import hudson.util.DescribableList;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;

import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;

import java.io.IOException;
import java.util.List;

import org.jvnet.mock_javamail.*;

import javax.mail.Message;

import org.junit.Test;

import static org.junit.Assert.*;

public class QuarantineCoreTest {

   @Rule
   public JenkinsRule j = new JenkinsRule();

   private String projectName = "x";
   protected String quarantineText = "quarantineReason";
   protected String user1Mail = "user1@mail.com";
   protected FreeStyleProject project;

   @Before
   public void setUp() throws Exception {
      java.util.logging.Logger.getLogger("com.gargoylesoftware.htmlunit").setLevel(java.util.logging.Level.SEVERE);
      project = j.createFreeStyleProject(projectName);
      DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>> publishers = new DescribableList<>(
              project);
      publishers.add(new QuarantineTestDataPublisher());
      QuarantinableJUnitResultArchiver archiver = new QuarantinableJUnitResultArchiver("*.xml");
      archiver.setTestDataPublishers(publishers);
      project.getPublishersList().add(archiver);

      j.jenkins.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());
      j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
      User u = User.get("user1");
      u.addProperty(new Mailer.UserProperty(user1Mail));
   }

   protected FreeStyleBuild addBuildFailure() throws Exception {
      FreeStyleBuild build;
      project.getBuildersList().add(new TestBuilder() {
         public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                 throws InterruptedException, IOException {
            return false;
         }
      });
      build = project.scheduleBuild2(0).get();
      project.getBuildersList().clear();
      return build;
   }

   protected FreeStyleBuild runBuildWithJUnitResult(final String xmlFileName) throws Exception {
      FreeStyleBuild build;
      project.getBuildersList().add(new TestBuilder() {
         public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                 throws InterruptedException, IOException {
            build.getWorkspace().child("junit.xml").copyFrom(getClass().getResource(xmlFileName));
            return true;
         }
      });
      build = project.scheduleBuild2(0).get();
      project.getBuildersList().clear();
      return build;
   }

   protected TestResult getResultsFromJUnitResult(final String xmlFileName) throws Exception {
      return runBuildWithJUnitResult(xmlFileName).getAction(TestResultAction.class).getResult();
   }

   @Test
   public void testAllTestsHaveQuarantineAction() throws Exception {
      TestResult tr = getResultsFromJUnitResult("junit-1-failure.xml");

      for (SuiteResult suite : tr.getSuites()) {
         for (CaseResult result : suite.getCases()) {
            assertNotNull(result.getTestAction(QuarantineTestAction.class));
         }
      }
   }

   @Test
   public void testNoTestsHaveQuarantineActionForStandardPublisher() throws Exception {
      project.getPublishersList().remove(QuarantinableJUnitResultArchiver.class);

      DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>> publishers = new DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>>(
              project);
      publishers.add(new QuarantineTestDataPublisher());
      JUnitResultArchiver jUnitResultArchiver = new JUnitResultArchiver("*.xml");
      jUnitResultArchiver.setTestDataPublishers(publishers);
      project.getPublishersList().add(jUnitResultArchiver);

      TestResult tr = getResultsFromJUnitResult("junit-1-failure.xml");

      for (SuiteResult suite : tr.getSuites()) {
         for (CaseResult result : suite.getCases()) {
            assertNull(result.getTestAction(QuarantineTestAction.class));
         }
      }
   }

   @Test
   public void testQuarantineSetAndRelease() throws Exception {
      TestResult tr = getResultsFromJUnitResult("junit-1-failure.xml");
      QuarantineTestAction action = tr.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class);
      action.quarantine("user1", "reason");
      assertTrue(action.isQuarantined());
      action.release();
      assertFalse(action.isQuarantined());
   }

   @Test
   public void testQuarantineIsStickyOnFailingTest() throws Exception {
      TestResult tr = getResultsFromJUnitResult("junit-1-failure.xml");

      QuarantineTestAction action = tr.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class);
      action.quarantine("user1", "reason");
      assertTrue(action.isQuarantined());

      tr = getResultsFromJUnitResult("junit-1-failure.xml");
      QuarantineTestAction action2 = tr.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class);

      assertTrue(tr.getOwner().getNumber() == 2);
      assertTrue(action2.isQuarantined());
      assertEquals(action.quarantinedByName(), action2.quarantinedByName());

   }

   @Test
   public void testQuarantineIsStickyOnPassingTest() throws Exception {
      TestResult tr = getResultsFromJUnitResult("junit-1-failure.xml");

      QuarantineTestAction action = tr.getSuite("SuiteA").getCase("TestA").getTestAction(QuarantineTestAction.class);
      action.quarantine("user1", "reason");
      assertTrue(action.isQuarantined());

      tr = getResultsFromJUnitResult("junit-1-failure.xml");
      QuarantineTestAction action2 = tr.getSuite("SuiteA").getCase("TestA").getTestAction(QuarantineTestAction.class);

      assertTrue(tr.getOwner().getNumber() == 2);
      assertTrue(action2.isQuarantined());
      assertEquals(action.quarantinedByName(), action2.quarantinedByName());

   }

   @Test
   public void testDontThrowNullptrExceptionWhenNoPreviousTestData() throws Exception {
      addBuildFailure();
      getResultsFromJUnitResult("junit-1-failure.xml");
   }

   @Test
   public void testUsesResultsFromLastGoodBuildWhenNoPreviousTestData() throws Exception {
      getResultsFromJUnitResult("junit-1-failure.xml");
      TestResult tr = getResultsFromJUnitResult("junit-1-failure.xml");
      for (SuiteResult suite : tr.getSuites()) {
         for (CaseResult result : suite.getCases()) {
            QuarantineTestAction action = result.getTestAction(QuarantineTestAction.class);
            action.quarantine("user1", "reason");
         }
      }

      getResultsFromJUnitResult("junit-dummy.xml"); // add a dummy file that doesn't have the test cases we're looking for
      tr = getResultsFromJUnitResult("junit-1-failure.xml");

      for (SuiteResult suite : tr.getSuites()) {
         for (CaseResult result : suite.getCases()) {
            QuarantineTestAction action  = result.getTestAction(QuarantineTestAction.class);
            assertEquals("reason",action.getReason());
         }
      }
   }

   @Test
   public void testResultIsOnlyMarkedAsLatestIfLatest() throws Exception {
      FreeStyleBuild build = runBuildWithJUnitResult("junit-1-failure.xml");
      TestResult tr1 = build.getAction(TestResultAction.class).getResult();
      QuarantineTestAction action1 = tr1.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class);

      assertTrue(action1.isLatestResult());

      build = runBuildWithJUnitResult("junit-1-failure.xml");
      TestResult tr2 = build.getAction(TestResultAction.class).getResult();
      QuarantineTestAction action2 = tr2.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class);

      assertFalse(action1.isLatestResult());
      assertTrue(action2.isLatestResult());
   }

   @Test
   public void testQuarantiningMakesFinalResultPass() throws Exception {
      FreeStyleBuild build = runBuildWithJUnitResult("junit-1-failure.xml");
      assertTrue(build.getResult() != Result.SUCCESS);

      TestResult tr = build.getAction(TestResultAction.class).getResult();
      QuarantineTestAction action = tr.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class);
      action.quarantine("user1", "reason");

      build = runBuildWithJUnitResult("junit-1-failure.xml");
      assertTrue(build.getResult() == Result.SUCCESS);
   }

   @Test
   public void testQuarantiningMakesFinalResultFailIfAnotherTestFails() throws Exception {
      FreeStyleBuild build = runBuildWithJUnitResult("junit-1-failure.xml");
      assertTrue(build.getResult() != Result.SUCCESS);

      TestResult tr = build.getAction(TestResultAction.class).getResult();
      QuarantineTestAction action = tr.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class);
      action.quarantine("user1", "reason");

      build = runBuildWithJUnitResult("junit-2-failures.xml");
      assertTrue(build.getResult() != Result.SUCCESS);
   }

   @Test
   public void testQuarantiningMakesFinalResultFailIfQuarantineReleased() throws Exception {
      FreeStyleBuild build = runBuildWithJUnitResult("junit-1-failure.xml");
      assertTrue(build.getResult() != Result.SUCCESS);

      TestResult tr = build.getAction(TestResultAction.class).getResult();
      QuarantineTestAction action = tr.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class);
      action.quarantine("user1", "reason");

      build = runBuildWithJUnitResult("junit-1-failure.xml");
      assertTrue(build.getResult() == Result.SUCCESS);
      tr = build.getAction(TestResultAction.class).getResult();
      action = tr.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class);
      action.release();

      build = runBuildWithJUnitResult("junit-1-failure.xml");
      System.out.println("result is " + build.getResult());
      assertTrue(build.getResult() != Result.SUCCESS);

   }

   @Test
   public void testQuarantineStatusNotLostIfTestNotRun() throws Exception {
      FreeStyleBuild build = runBuildWithJUnitResult("junit-1-failure.xml");
      assertTrue(build.getResult() != Result.SUCCESS);

      TestResult tr = build.getAction(TestResultAction.class).getResult();
      QuarantineTestAction action = tr.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class);
      action.quarantine("user1", "reason");

      build = runBuildWithJUnitResult("junit-1-failure-missing.xml");
      assertTrue(build.getResult() == Result.SUCCESS);

      build = runBuildWithJUnitResult("junit-1-failure.xml");
      assertTrue(build.getResult() == Result.SUCCESS);
   }

   @Test
   public void testQuarantinedTestsAreInReport() throws Exception {
      TestResult tr = getResultsFromJUnitResult("junit-1-failure.xml");

      tr.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class).quarantine("user1", "reason");
      tr.getSuite("SuiteB").getCase("TestA").getTestAction(QuarantineTestAction.class).quarantine("user1", "reason");

      QuarantinedTestsReport report = new QuarantinedTestsReport();

      assertEquals(2, report.getQuarantinedTests().size());
      assertTrue(report.getQuarantinedTests().contains(tr.getSuite("SuiteA").getCase("TestB")));
      assertTrue(report.getQuarantinedTests().contains(tr.getSuite("SuiteB").getCase("TestA")));
   }

   @Test
   public void testQuarantineReportGetNumberOfSuccessivePasses() throws Exception {
      TestResult tr = getResultsFromJUnitResult("junit-no-failure.xml");
      tr.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class).quarantine("user1", "reason");

      QuarantinedTestsReport report = new QuarantinedTestsReport();
      assertEquals(1, report.getNumberOfSuccessivePasses(report.getQuarantinedTests().get(0)));

      runBuildWithJUnitResult("junit-no-failure.xml");
      report = new QuarantinedTestsReport();
      assertEquals(2, report.getNumberOfSuccessivePasses(report.getQuarantinedTests().get(0)));

      runBuildWithJUnitResult("junit-1-failure.xml");
      report = new QuarantinedTestsReport();
      assertEquals(0, report.getNumberOfSuccessivePasses(report.getQuarantinedTests().get(0)));

      runBuildWithJUnitResult("junit-no-failure.xml");
      report = new QuarantinedTestsReport();
      assertEquals(1, report.getNumberOfSuccessivePasses(report.getQuarantinedTests().get(0)));
   }

   @Test
   public void testSendsEmailWhenQuarantinedFails() throws Exception {
      Mailbox.clearAll();
      TestResult tr = getResultsFromJUnitResult("junit-1-failure.xml");
      tr.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class).quarantine("user1", "reason");

      getResultsFromJUnitResult("junit-1-failure.xml");

      List<Message> inbox = Mailbox.get(user1Mail);
      assertEquals(1, inbox.size());
   }

   @Test
   public void testDoesntEmailWhenQuarantinedPasses() throws Exception {
      Mailbox.clearAll();
      TestResult tr = getResultsFromJUnitResult("junit-1-failure.xml");
      tr.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class).quarantine("user1", "reason");

      getResultsFromJUnitResult("junit-no-failure.xml");

      List<Message> inbox = Mailbox.get(user1Mail);
      assertEquals(0, inbox.size());
   }

   @Test
   public void testTestEmailsAreCollatedWhenMultipleQuarantinedFail() throws Exception {
      Mailbox.clearAll();
      TestResult tr = getResultsFromJUnitResult("junit-1-failure.xml");
      tr.getSuite("SuiteA").getCase("TestB").getTestAction(QuarantineTestAction.class).quarantine("user1", "reason");
      tr.getSuite("SuiteB").getCase("TestA").getTestAction(QuarantineTestAction.class).quarantine("user1", "reason");

      getResultsFromJUnitResult("junit-2-failures.xml");

      List<Message> inbox = Mailbox.get(user1Mail);
      assertEquals(1, inbox.size());
   }

}
