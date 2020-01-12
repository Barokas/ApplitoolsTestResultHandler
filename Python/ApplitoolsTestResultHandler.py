import re
import time
from math import floor
from uuid import uuid4

import requests
import shutil
import os
from enum import Enum
from enum import IntEnum
from email.utils import formatdate


class ResultStatus(Enum):
    PASSED = 'passed'
    FAILED = 'failed'
    NEW = 'new'
    MISSING = 'missing'


class StatusCode(IntEnum):
    OK = 200
    CREATED = 201
    ACCEPTED = 202
    GONE = 410


class ApplitoolsTestResultsHandler:

    def _get_session_id(self, test_results):
        pattern = '^' + re.escape(self.server_URL) + '\/app\/batches\/\d+\/(?P<sessionId>\d+).*$'
        return re.findall(pattern, test_results.url)[0]

    def _get_batch_id(self, test_results):
        pattern = '^' + re.escape(self.server_URL) + '\/app\/batches\/(?P<batchId>\d+).*$'
        return re.findall(pattern, test_results.url)[0]

    def _get_server_url(self, test_results):
        return test_results.url[0:test_results.url.find("/app/batches")]

    def __init__(self, test_results, view_key):
        self.view_key = view_key
        self.test_results = test_results
        self.server_URL = self._get_server_url(test_results)
        self.session_ID = self._get_session_id(test_results)
        self.batch_ID = self._get_batch_id(test_results)
        self.test_JSON = self.get_test_json()
        self.retry_request_interval = 500
        self.long_request_delay = 2
        self.max_long_request_delay = 10
        self.default_timeout = 30
        self.reduced_timeout = 15
        self.long_request_delay_multiplicative_factor = 1.5
        self.counter = 0

    def calculate_step_results(self):
        expected = self.test_JSON['expectedAppOutput']
        actual = self.test_JSON['actualAppOutput']
        steps = max(len(expected), len(actual))
        steps_result = list()
        for i in range(steps):
            if actual[i] is None:
                steps_result.append(ResultStatus.MISSING)
            elif expected[i] is None:
                steps_result.append(ResultStatus.NEW)
            elif actual[i]['isMatching']:
                steps_result.append(ResultStatus.PASSED)
            else:
                steps_result.append(ResultStatus.FAILED)
        return steps_result

    def download_diffs(self, path):
        path = self.prepare_path(path)
        step_states = self.calculate_step_results()
        for i in range(len(step_states)):
            if step_states[i] is ResultStatus.FAILED:
                image_url = self.server_URL + '/api/sessions/batches/' + self.batch_ID + '/' + self.session_ID + '/steps/' + str(
                    i + 1) + '/diff'
                diff_path = path + "/diff_step_" + str(i + 1) + ".jpg"
                self.image_from_url_to_file(url=image_url, path=diff_path)
            else:
                print("No Diff image in step " + str(i + 1) + '\n')

    def download_images(self, path):
        self.download_baseline_images(path=path)
        self.download_current_images(path=path)

    def download_current_images(self, path):
        path = self.prepare_path(path)
        for i in range(self.test_results.steps):
            image_id = self.get_image_id("actualAppOutput", i)
            if image_id is not None:
                image_url = self.server_URL + '/api/images/' + image_id
                curr_path = path + "/current_step_" + str(i + 1) + ".jpg"
                self.image_from_url_to_file(url=image_url, path=curr_path)

    def download_baseline_images(self, path):
        path = self.prepare_path(path)

        for i in range(self.test_results.steps):
            image_id = self.get_image_id("expectedAppOutput", i)
            if image_id is not None:
                image_url = self.server_URL + '/api/images/' + image_id
                base_path = path + "/baseline_step_" + str(i + 1) + ".jpg"
                self.image_from_url_to_file(url=image_url, path=base_path)

    def image_from_url_to_file(self, url, path):
        with self.send_long_request('GET', url) as resp, open(path, 'wb') as out_file:
            shutil.copyfileobj(resp.raw, out_file)

    def prepare_path(self, path):
        path = path + "/" + self.batch_ID + "/" + self.session_ID
        if not os.path.exists(path):
            os.makedirs(path)
        return path

    def get_image_id(self, image_type, step):
        try:
            return self.test_JSON[image_type][step]['image']['id']
        except TypeError:
            if image_type == "actualAppOutput":
                print("The current image in step " + str(step + 1) + ' is missing\n')
            elif image_type == "expectedAppOutput":
                print("The baseline image in step " + str(step + 1) + ' is missing\n')
        return None

    def get_test_json(self):
        request_url = str(self.server_URL) + '/api/sessions/batches/' + str(self.batch_ID) + '/' + str(
            self.session_ID) + '/?apiKey=' + str(self.view_key) + '&format=json'
        test_json = requests.get(request_url.encode('ascii', 'ignore')).json()
        test_json = dict([(str(k), v) for k, v in test_json.items()])
        return test_json

    def send_long_request(self, request_type, url):
        request = self.create_request(request_type, url)
        response = self.send_request(request)
        return self.long_request_check_status(response)

    def create_request(self, request_type, url, request=None):
        if request is None:
            request = {}
        current_date = formatdate(timeval=None, localtime=False, usegmt=True)
        headers = {
            "Eyes-Expect": "202+location",
            "Eyes-Date": current_date
        }
        request["headers"] = headers
        request["url"] = url
        request["request_type"] = request_type
        return request

    def send_request(self, request, retry=1, delay_before_retry=False):
        self.counter += 1
        request_id = str(self.counter) + "--" + str(uuid4())

        headers = request.get("headers")
        request_type = request.get("request_type")
        url = request.get("url")
        url = url + "?apiKey=" + self.view_key

        headers["x-applitools-eyes-client-request-id"] = request_id

        try:
            if request_type is 'GET':
                response = requests.get(
                    url,
                    headers=headers,
                    stream=True
                )
            elif request_type is 'POST':
                response = requests.post(
                    url,
                    headers=headers,
                    stream=True
                )
            elif request_type is 'DELETE':
                response = requests.delete(
                    url,
                    headers=headers,
                    stream=True

                )
            else:
                raise Exception("Not a valid request type")
            return response

        except Exception as e:
            print("Error: " + str(e))
            if retry > 0:
                if delay_before_retry:
                    time.sleep(self.retry_request_interval)
                    return self.send_request(request, retry - 1, delay_before_retry)
                return self.send_request(request, retry - 1, delay_before_retry)
            raise Exception("Error: " + str(e))

    def long_request_check_status(self, response):
        status = response.status_code

        # OK
        if status is 200:
            return response

        # Accepted
        elif status is 202:
            url = response.headers.get("location")
            request = self.create_request('GET', url)
            request_response = self.long_request_loop(request, self.long_request_delay)
            return self.long_request_check_status(request_response)

        # Created
        elif status is 201:
            url = response.headers.get("location")
            request = self.create_request('DELETE', url)
            return self.send_request(request)

        # Gone
        elif status is 410:
            raise Exception("The server task has gone")
        else:
            raise Exception("Unknown error during long request: " + response.status_code)

    def long_request_loop(self, request, delay):
        delay = min(self.max_long_request_delay, floor(delay * self.long_request_delay_multiplicative_factor))
        print("Still running.. Retrying in " + str(delay) + " s")
        time.sleep(delay)

        response = self.send_request(request)

        if response.status_code is not 200:
            return response
        return self.long_request_loop(request, delay)


