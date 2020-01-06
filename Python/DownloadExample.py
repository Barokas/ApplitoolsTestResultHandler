from applitools.common import BatchInfo
from selenium import webdriver
from applitools.selenium import Eyes

from ApplitoolsTestResultHandler import ApplitoolsTestResultsHandler


class DownloadExample:

    eyes = Eyes()

    # Initialize the eyes SDK and set your private API key.
    eyes.api_key = 'APPLITOOLS_API_KEY'
    view_key = "APPLITOOLS_VIEW_KEY"

    try:
        # batch_info = BatchInfo("Batch name")
        # eyes.batch = batch_info

        # Open a Chrome browser.
        driver = webdriver.Chrome()

        # Start the test and set the browser's viewport size to 800x600.
        eyes.open(driver, "Hello World!", "Downloading Diffs Test", {'width': 800, 'height': 600})

        # Navigate the browser to the "hello world!" web-site.
        driver.get('https://applitools.com/helloworld?diff1')

        # Visual checkpoint #1.
        eyes.check_window('Hello!')

        # Click the 'Click me!' button.
        driver.find_element_by_css_selector('button').click()

        # Visual checkpoint #2.
        eyes.check_window('Click!')

        # End the test.
        test_results = eyes.close(False)
        test_result_handler = ApplitoolsTestResultsHandler(test_results, view_key)

        test_result_handler.download_diffs("PathToDownloadTo")
        test_result_handler.download_images("PathToDownloadTo")

    finally:

        # Close the browser.
        driver.quit()
