using System;
using OpenQA.Selenium;
using OpenQA.Selenium.Chrome;
using System.Threading;
using Applitools.Selenium;
using System.Drawing;
using ApplitoolsTestResultsHandler;

namespace ProjectName
{

    public class DownloadDiffExample
    {
        public static void Main()
        {
            // Open a Chrome browser.
            var driver = new ChromeDriver();

            // Initialize the eyes SDK and set your private API key.
            var eyes = new Eyes();
            eyes.ApiKey = "Applitools_ApiKey";

            try
            {
                // Start the test and set the browser's viewport size to 800x600.
                eyes.Open(driver, "Hello World!", "My first Selenium C# test!", new Size(800, 600));

                // Navigate the browser to the "hello world!" web-site.
                driver.Url = "https://applitools.com/helloworld";

                // Visual checkpoint #1.
                eyes.CheckWindow("Hello!");
             
                //Click the "Click me!" button.
                driver.FindElement(By.TagName("button")).Click();

                //Visual checkpoint #2.
                eyes.CheckWindow("click!");

                // End visual testing. Validate visual correctness.
                Applitools.TestResults result = eyes.Close(false);

                //Link to batch result.
                Console.WriteLine(String.Format("This is the link for the Batch Result: {0}", result.Url));

                ApplitoolsTestResultsHandler.ApplitoolsTestResultsHandler testResultHandler = new ApplitoolsTestResultsHandler.ApplitoolsTestResultsHandler("Applitools_ViewKey", result);

                //Optional Setting this prefix will determine the structure of the repository for the downloaded
                testResultHandler.setPathPrefixStructure("TestName/AppName/viewport/hostingOS/hostingApp");
               
                // Download both the Baseline and the Current images to the folder specified in Path.
                testResultHandler.downloadImages("PathToDownloadImages");

                // Download the Current images to the folder specified in Path.
                testResultHandler.downloadCurrentImages("PathToDownloadImages");

                // Download the Baseline images to the folder specified in Path.
                testResultHandler.downloadBaselineImages("PathToDownloadImages");

                // Download Diffs to the folder specified in Path.
                testResultHandler.downloadDiffs("PathToDownloadImages");
            }
            catch (Exception ex)
            {
                Console.WriteLine(ex);
            }
            finally
            {
                // Close the browser.
                driver.Quit();

                // If the test was aborted before eyes.Close was called, ends the test as aborted.
                eyes.AbortIfNotClosed();
            }
        }
    }
}
