import re
import requests
import urllib3
import shutil
import certifi
import os
from enum import Enum


class ResultStatus(Enum):
    PASSED = 'passed'
    FAILED = 'failed'
    NEW = 'new'
    MISSINIG = 'missing'


class ApplitoolsTestResultsHandler:
    def _get_session_id(self, testResults):
        pattern = '^' + re.escape(self.server_URL) + '\/app\/batches\/\d+\/(?P<sessionId>\d+).*$'
        return re.findall(pattern, testResults.url)[0]

    def _get_batch_id(self, testResults):
        pattern = '^' + re.escape(self.server_URL) + '\/app\/batches\/(?P<batchId>\d+).*$'
        return re.findall(pattern, testResults.url)[0]

    def _get_server_url(self, testResults):
        return testResults.url[0:testResults.url.find("/app/batches")]

    def __init__(self, testResults, viewKey):
        self.viewKey = viewKey
        self.testResults = testResults
        self.server_URL = self._get_server_url(testResults)
        self.session_ID = self._get_session_id(testResults)
        self.batch_ID = self._get_batch_id(testResults)
        self.test_JSON = self._get_test_json()

    def calculate_step_results(self):
        expected = self.test_JSON['expectedAppOutput']
        actual = self.test_JSON['actualAppOutput']
        steps = max(len(expected), len(actual))
        stepsResult = list()
        for i in range(steps):
            if actual[i] is None:
                stepsResult.append(ResultStatus.MISSINIG)
            elif expected[i] is None:
                stepsResult.append(ResultStatus.NEW)

            elif actual[i]['isMatching'] == True:
                stepsResult.append(ResultStatus.PASSED)
            else:
                stepsResult.append(ResultStatus.FAILED)
        return stepsResult

    def download_diffs(self, Path):
        Path = self._prepare_path(Path)
        stepStates = self.calculate_step_results()
        for i in range(len(stepStates)):
            if stepStates[i] is ResultStatus.FAILED:
                image_URL = self.server_URL + '/api/sessions/batches/' + self.batch_ID + '/' + self.session_ID + '/steps/' + str(
                    i + 1) + '/diff?apiKey=' + self.viewKey
                diff_path = Path + "/diff_step_" + str(i + 1) + ".jpg"
                self._image_from_URL_toFile(url=image_URL, path=diff_path)
            else:
                print ("No Diff image in step " + str(i + 1) + '\n')

    def download_images(self, Path):
        self.download_baseline_images(Path=Path)
        self.download_current_images(Path=Path)

    def download_current_images(self, Path):
        Path = self._prepare_path(Path)
        for i in range(self.testResults.steps):
            imageID = self._get_image_id("actualAppOutput", i)
            if imageID is not None:
                image_URL = self.server_URL + '/api/images/' + imageID + '?apiKey=' + self.viewKey
                curr_path = Path + "/current_step_" + str(i + 1) + ".jpg"
                self._image_from_URL_toFile(url=image_URL, path=curr_path)

    def download_baseline_images(self, Path):
        Path = self._prepare_path(Path)

        for i in range(self.testResults.steps):
            imageID = self._get_image_id("expectedAppOutput", i)
            if imageID is not None:
                # path = Path + "/baseline_step_" + str(i + 1) + ".jpg"
                image_URL = self.server_URL + '/api/images/' + imageID + '?apiKey=' + self.viewKey
                base_path = Path + "/baseline_step_" + str(i + 1) + ".jpg"
                self._image_from_URL_toFile(url=image_URL, path=base_path)

    def _image_from_URL_toFile(self, url, path):
        c = urllib3.PoolManager(cert_reqs='CERT_REQUIRED', ca_certs=certifi.where())
        with c.request('GET', url, preload_content=False) as resp, open(path, 'wb') as out_file:
            shutil.copyfileobj(resp, out_file)
        resp.release_conn()

    def _prepare_path(self, Path):
        Path = Path + "/" + self.batch_ID + "/" + self.session_ID
        if not os.path.exists(Path):
            os.makedirs(Path)
        return Path

    def _get_image_id(self, image_type, step):
        try:
            return self.test_JSON[image_type][step]['image']['id']
        except TypeError:
            if image_type =="actualAppOutput":
                print ("The current image in step " + str(step + 1) + ' is missing\n')
            elif image_type== "expectedAppOutput":
                print ("The baseline image in step " + str(step + 1) + ' is missing\n')
        return None

    def _get_test_json(self):
        request_URL = str(self.server_URL) + '/api/sessions/batches/' + str(self.batch_ID) + '/' + str(
            self.session_ID) + '/?apiKey=' + str(self.viewKey) + '&format=json'
        testJson = requests.get(request_URL.encode('ascii', 'ignore')).json()
        testJson = dict([(str(k), v) for k, v in testJson.items()])
        return testJson
