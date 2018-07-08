require 'eyes_selenium'
require 'httparty'
require 'selenium-webdriver'
require 'json'
require './ApplitoolsTestResultHandler'

# Initialize the eyes SDK and set your private API key.
eyes = Applitools::Selenium::Eyes.new
eyes.api_key = ENV['APPLITOOLS_APIKEY']
viewKey =ENV['APPLITOOLS_VIEWKEY']
# path='/Users/liranbarokas/Desktop/Development/ApplitoolsTestResultHandler/Ruby/tempImages'

# Open a Chrome Browser.
driver = Selenium::WebDriver.for :chrome

begin
  # Start the test and set the browser's viewport size to 800x600.
  driver = eyes.open(app_name: 'Hello World!', test_name: 'My first Selenium Ruby test!',
  viewport_size: {width:800, height:600}, driver: driver)

  # Navigate the browser to the "hello world!" web-site.
  driver.get 'https://applitools.com/'
  # Visual checkpoint #1.
  eyes.check_window 'Hello!'

  results = eyes.close(false)
  trh=ApplitoolsTestResultHandler.new(results,viewKey)
  trh.set_path_prefix_structure('/#{AppName}/#{testName}/#{viewport}/#{hostingOS}/#{hostingApp}/')  # Default value will be /#{AppName}/
  trh.download_images()
  trh.download_diffs()
  print(trh.calculate_step_results())

ensure
  # Close the browser.
  driver.quit

  # If the test was aborted before eyes.close was called, ends the test as aborted.
  eyes.abort_if_not_closed
end
