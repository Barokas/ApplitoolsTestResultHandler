import com.applitools.eyes.RectangleSize;
import com.applitools.eyes.TestResults;
import com.applitools.eyes.selenium.Eyes;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;


public class DownloadDiffExample {

    public static void main(String[] args) throws Exception {


        WebDriver driver = new ChromeDriver();
        Eyes eyes = new Eyes();

//         This is your api key, make sure you use it in all your tests.
        eyes.setApiKey(System.getenv("Applitools_ApiKey"));
        try {
            // Start visual testing with browser viewport set to 1000x600.
            // Make sure to use the returned driver from this point on.
            eyes.open(driver, "Hello World!", "My first Selenium Java test!", new RectangleSize(800, 600));

            // Navigate the browser to the "hello world!" web-site.
            driver.get("https://applitools.com/helloworld");

            // Visual checkpoint #1.
            eyes.checkWindow("Hello!");

            // Click the "Click me!" button.
            driver.findElement(By.tagName("button")).click();

            // Visual checkpoint #2.
            eyes.checkWindow("Click!");

//             End visual testing. Validate visual correctness.
            TestResults testResult= eyes.close(false);
            ApplitoolsTestResultsHandler testResultHandler = new ApplitoolsTestResultsHandler(testResult,System.getenv("Applitools_ViewKey"));
            testResultHandler.downloadImages(System.getenv("PathToDownloadImages"));
            String[] names = testResultHandler.getStepsNames();
            ResultStatus[] results = testResultHandler.calculateStepResults();
            testResultHandler.downloadDiffs(System.getenv("PathToDownloadImages"));
            testResultHandler.downloadAnimateGiff(System.getenv("PathToDownloadImages"));  // option one - Default Time between Frames is 500 MS
//            testResultHandler.downloadAnimateGiff(System.getenv("PathToDownloadImages"),300); // option two.
        }

        finally {
            // Abort Session in case of an unexpected error.
            eyes.abortIfNotClosed();
            driver.close();
        }
    }
}