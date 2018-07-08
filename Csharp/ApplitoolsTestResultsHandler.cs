using System;
using Applitools;
using Newtonsoft.Json.Linq;
using System.Text.RegularExpressions;
using System.Net;
using System.IO;

namespace ApplitoolsTestResultsHandler
{
    public enum RESULT_STATUS
    {
        Pass, Unresolved, New, Missing
    }

    public enum IMAGE_TYPE
    {
        Baseline, Current
    }

    public class ApplitoolsTestResultsHandler
    {
        private string ViewKey;
        private TestResults testresult;
        private string ServerURL;
        private string batchID;
        private string sessionID;
        private RESULT_STATUS[] stepsResult;
        private JObject testJson;
        private string prefix;

        // Json keys
        private const string START_INFO = "startInfo";
        private const string SCENARIO_NAME = "scenarioIdOrName";
        private const string APP_NAME = "appIdOrName";
        private const string ENVIRONMENT = "environment";
        private const string DISPLAY_SIZE = "displaySize";
        private const string HEIGHT = "height";
        private const string WIDTH = "width";
        private const string OS = "os";
        private const string HOSTING_APP = "hostingApp";

        public ApplitoolsTestResultsHandler(string viewKey, TestResults testResult)
        {
            testresult = testResult;
            ViewKey = viewKey;
            setServerURL(testResult);
            setbatchID(testResult);
            setsessionID(testResult);
            setTestJson();
            setStepResults();
        }

        public RESULT_STATUS[] calculateStepResults()
        {
            return this.stepsResult;
        }

        /**
         *     @brief   Initializes the path's prefix and replaces keywords with values
         *     @Note    Supported keywords - TestName, APpName, viewport,
         *              hostingOS, hostingApp
         * 
         */
        public void setPathPrefixStructure(string pathPrefix)
        {
            pathPrefix = pathPrefix.Replace("TestName", this.getTestName());
            pathPrefix = pathPrefix.Replace("AppName", this.getAppName());
            pathPrefix = pathPrefix.Replace("viewport", this.getViewportSize());
            pathPrefix = pathPrefix.Replace("hostingOS", this.getHostingOS());
            pathPrefix = pathPrefix.Replace("hostingApp", this.getHostingApp());
            prefix = pathPrefix;
        }

        /**
         *    @brief    Downloads the images that differs from the baseline
         *    @param    path    The path in which the images will be saved
         */
        public void downloadDiffs(string Path)
        {
            RESULT_STATUS[] stepStates = this.stepsResult;
            WebClient webClient = new WebClient();
            int countDiffs = 0;
            for (int i = 0; i < stepStates.Length; ++i)
            {
                if (stepsResult[i] == RESULT_STATUS.Unresolved)
                {
                    ++countDiffs;
                    if (countDiffs == 1)
                    {
                        Path = preparePath(Path);
                    }
                    string filePath = Path + "/diff_step_" + (i + 1).ToString() + ".jpg";
                    string download_image_URL = this.ServerURL + "/api/sessions/batches/" + this.batchID + "/" + this.sessionID + "/steps/" + (i + 1).ToString() + "/diff?apiKey=" + this.ViewKey;
                    webClient.DownloadFile(download_image_URL, filePath);
                }
                else
                {
                    Console.Write("No Diff Image in step " + (i + 1).ToString() + " \n");
                }
            }
        }

        /**
         *     @brief   Downloads the current images and baseline images
         *     @param   Path     The path in which the images will be saved
         */
        public void downloadImages(string Path)
        {
            this.downloadCurrentImages(Path);
            this.downloadBaselineImages(Path);
        }

        /**
         *     @brief   Downloads the baseline images
         *     @param   Path     The path in which the images will be saved
         */
        public void downloadBaselineImages(string Path)
        {
            WebClient webClient = new WebClient();
            string URL = "";
            string FullPath = "";
            int counterForBaseline = 0;
            for (int i = 0; i < this.stepsResult.Length; ++i)
            {
                if (stepsResult[i] != RESULT_STATUS.New)
                {
                    ++counterForBaseline;
                }
                URL = this.ServerURL + "/api/images/" + this.getImageID(IMAGE_TYPE.Baseline, i) + "?apiKey=" + this.ViewKey;
                if (counterForBaseline == 1)
                {
                    Path = preparePath(Path);
                }
                FullPath = Path + "/baseline_step_" + (i + 1).ToString() + ".jpg";
                try
                {
                    webClient.DownloadFile(URL, FullPath);
                }
                catch
                {
                    Console.Write("The baseline image in step " + (i + 1).ToString() + " is missing\n");
                }
            }
        }

        /**
        *     @brief   Downloads the current images
        *     @param   Path     The path in which the images will be saved
        */
        public void downloadCurrentImages(string Path)
        {
            WebClient webClient = new WebClient();
            string URL = "";
            string FullPath = "";
            int counterForCurrent = 0;
            for (int i = 0; i < this.stepsResult.Length; ++i)
            {
                if (stepsResult[i] != RESULT_STATUS.Missing)
                {
                    counterForCurrent++;
                }
                URL = this.ServerURL + "/api/images/" + this.getImageID(IMAGE_TYPE.Current, i) + "?apiKey=" + this.ViewKey;
                if (counterForCurrent == 1)
                {
                    Path = preparePath(Path);
                }
                FullPath = Path + "/actual_step_" + (i + 1).ToString() + ".jpg";
                try
                {
                    webClient.DownloadFile(URL, FullPath);
                }
                catch
                {
                    Console.Write("The current image in step " + (i + 1).ToString() + " is missing\n");
                }
            }
        }

        private void setServerURL(TestResults testresult)
        {
            int endOfServerUrlLocation = testresult.Url.IndexOf("/app/session");
            if (endOfServerUrlLocation < 1)
            {
                endOfServerUrlLocation = testresult.Url.IndexOf("/app/batches");
            }
            this.ServerURL = testresult.Url.Substring(0, endOfServerUrlLocation);

        }

        /*
         *  @brief  Returns the value of the test name from the json
         *  @returns    String value representing the test name
         */
        private string getTestName() {
            try {
                return this.testJson[START_INFO][SCENARIO_NAME].ToString();
            }
            catch {
                return "TestName";
            }
        }

        /*
         *  @brief  Returns the value of the application name from the json
         *  @returns    String value representing the application name
         */
        private string getAppName()
        {
            try
            {
                return this.testJson[START_INFO][APP_NAME].ToString();
            }
            catch
            {
                return "AppName";
            }
        }

        /*
         *  @brief  Returns the value of the view port size from the json
         *  @returns    String value representing the view port size
         */
        private string getViewportSize()
        {
            try
            {
                var displaySize = this.testJson[START_INFO][ENVIRONMENT][DISPLAY_SIZE];
                return displaySize[WIDTH].ToString() + "x" + displaySize[HEIGHT].ToString();
            }
            catch
            {
                return "widthxheight";
            }
        }

        /*
         *  @brief  Returns the value of the hosting os from the json
         *  @returns    String value representing the os
         */
        private string getHostingOS()
        {
            try
            {
                return this.testJson[START_INFO][ENVIRONMENT][OS].ToString();
            }
            catch
            {
                return "os";
            }
        }

        /*
         *  @brief  Returns the value of the hosting application from the json
         *  @returns    String value representing the hosting application
         */
        private string getHostingApp()
        {
            try
            {
                return this.testJson[START_INFO][ENVIRONMENT][HOSTING_APP].ToString();
            }
            catch
            {
                return "hostingApp";
            }
        }

        private void setTestJson()
        {
            string requestURL = this.ServerURL + @"/api/sessions/batches/" + this.batchID + @"/" + this.sessionID + @"?apiKey=" + this.ViewKey + @"&format=json";
            var json = new WebClient().DownloadString(requestURL);
            this.testJson = JObject.Parse(json);
        }

        private void setbatchID(TestResults testresult)
        {
            string URL = testresult.Url;
            string temp = "^" + this.ServerURL + @"/app/sessions/(?<batchId>\d+).*$";
            Match match = Regex.Match(URL, "^" + this.ServerURL + @"/app/batches/(?<batchId>\d+).*$");
            this.batchID = match.Groups[1].Value;
        }

        private void setsessionID(TestResults testresult)
        {
            string URL = testresult.Url;
            Match match = Regex.Match(URL, "^" + this.ServerURL + @"/app/batches/\d+/(?<sessionId>\d+).*$");
            this.sessionID = match.Groups[1].Value;
        }

        /**
         *  @brief      Aggrigate the path and create the folder if it doesn't exist
         *  @returns    String of the aggrigated path
         */
        private string preparePath(string path)
        {
            path += "/" + prefix;
            String batchAndSessionSuffix = String.Format("/{0}/{1}", batchID, sessionID);
            if (!path.Contains(batchAndSessionSuffix))
            {
                path += batchAndSessionSuffix;
                bool folderExists = Directory.Exists(path);
                if (!folderExists)
                {
                    Directory.CreateDirectory(path);  
                }
            }
            return path;
        }

        /**
         * @brief   Compares the actual results with the expected using the json
         */
        private void setStepResults()
        {
            var actual = this.testJson["actualAppOutput"];
            var expected = this.testJson["expectedAppOutput"];
            RESULT_STATUS[] returnResult = new RESULT_STATUS[Math.Max(((JArray)actual).Count, ((JArray)expected).Count)];

            for (int i = 0; i < Math.Max(((JArray)actual).Count, ((JArray)expected).Count); ++i)
            {
                if (actual[i].ToString().Equals(String.Empty))
                {
                    returnResult[i] = RESULT_STATUS.Missing;
                }
                else if (expected[i].ToString().Equals(String.Empty))
                {
                    returnResult[i] = RESULT_STATUS.New;

                }
                else if (!(bool)actual[i]["isMatching"])
                {
                    returnResult[i] = RESULT_STATUS.Unresolved;
                }
                else
                {
                    returnResult[i] = RESULT_STATUS.Pass;
                }
            }
            this.stepsResult = returnResult;
        }

        /**
         * @brief   Reads the image id from the json in accordance with the 
         *          received type and the step number
         * @returns string containing the image's id, empty string if not found
         */
        private string getImageID(IMAGE_TYPE type, int stepNum)
        {
            JArray json = new JArray();
            if (type == IMAGE_TYPE.Baseline)
            {
                json = (JArray)this.testJson["expectedAppOutput"];
            }
            else if (type == IMAGE_TYPE.Current)
            {
                json = (JArray)this.testJson["actualAppOutput"];
            }
            try
            {
                return (string)json[stepNum]["image"]["id"];
            }
            catch
            {
                return "";
            }
        }
    }
} //endof ApplitoolsTestResultsHandler namespace