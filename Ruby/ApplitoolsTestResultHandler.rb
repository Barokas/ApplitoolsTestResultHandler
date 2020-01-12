require 'json'
require 'fileutils'
require 'net/http'
require 'faraday'
require 'time'
require 'securerandom'


class ApplitoolsTestResultHandler
  @@result_status = {pass:"PASSED", unresolved:"UNRESOLVED", new:"NEW", miss:"MISSING"}

  def initialize(test_results, view_key)
    @test_results = test_results
    @view_key=view_key
    @server_url = get_server_url
    @session_id = get_session_id
    @batch_id = get_batch_id
    @test_data = read_test_data
    @step_result = get_step_results
    @retry_request_interval = 500
    @long_request_delay = 2
    @max_long_request_delay = 10
    @default_timeout = 30
    @reduced_timeout = 15
    @long_request_delay_multiplicative_factor = 1.5
    @counter = 0
    set_path_prefix_structure('')
  end

  def set_path_prefix_structure(path_template)
    path=String.new(path_template)
    path=path.sub('#{testName}', get_test_name)
    path=path.sub('#{AppName}', get_app_name)
    path=path.sub('#{viewport}', get_viewport_size)
    path=path.sub('#{hostingOS}', get_hosting_os)
    path=path.sub('#{hostingApp}', get_hosting_app)
    path+=@session_id+'/'+@batch_id+'/'

    @path_prefix=path
  end

  def read_test_data
    url = @server_url+"/api/sessions/batches/"+get_batch_id+"/"+get_session_id+"/?ApiKey="+@view_key+"&format=json"
    response = Faraday.get(url)
    JSON.parse(response.body)
  end

  def get_server_url
    @test_results.url.split("/app/")[0]
  end

  def get_test_name
    @test_data['startInfo']['scenarioName']
  end

  def get_app_name
    @test_data['startInfo']['appName']
  end

  def get_viewport_size
    @test_data['startInfo']['environment']['displaySize']['width'].to_s+'x'+@test_data['startInfo']['environment']['displaySize']['height'].to_s
  end

  def get_hosting_os
    @test_data['startInfo']['environment']['os']
  end

  def get_hosting_app
    @test_data['startInfo']['environment']['hostingApp']
  end

  def get_session_id
    /batches\/\d+\/(?<sessionId>\d+)/.match(@test_results.url)[1]
  end

  def get_batch_id
    /batches\/(?<batchId>\d+)/.match(@test_results.url)[1]
  end

  def calculate_step_results
    @step_result
  end

  def get_step_results
    expected=@test_data['expectedAppOutput']
    actual=@test_data['actualAppOutput']
    steps=[expected.size,actual.size].max
    ret_step_results=Array.new(steps)
    (0..steps - 1).each { |i|
      if expected[i] == nil
        ret_step_results[i] = @@result_status[:new]
      elsif actual[i] == nil
        ret_step_results[i] = @@result_status[:miss]
      elsif actual[i]['isMatching']
        ret_step_results[i] = @@result_status[:pass]
      else
        ret_step_results[i] = @@result_status[:unresolved]
      end
    }

    ret_step_results
  end


  def get_step_names
    expected=@test_data['expectedAppOutput']
    actual=@test_data['actualAppOutput']
    num_steps=[expected.size, actual.size].max
    ret_step_names=Array.new(num_steps)

    (0..(num_steps - 1)).each { |i|
      if @step_result[i] != @@result_status[:new]
        ret_step_names[i] = expected[i]['tag']
      else
        ret_step_names[i] = actual[i]['tag']
      end

    }
    ret_step_names
  end

  def get_images_urls_by_type(image_type)
    image_data=@test_data[image_type]

    images_urls= Hash.new
    (0...image_data.size).each { |index|
      if (image_type == 'actualAppOutput' and @step_result[index] != @@result_status[:miss]) or (image_type == 'expectedAppOutput' and @step_result[index] != @@result_status[:new])
        image_id = image_data[index]["image"]["id"]
        url = "#{@server_url}/api/images/#{image_id}"
        images_urls[index] = url % [index]
      else
        images_urls[index] = nil
      end
    }
    images_urls
  end

  def download_current (destination=Dir.pwd)
    destination = prep_path(destination)
    current_urls = get_images_urls_by_type('actualAppOutput')
    download_images_from_url(current_urls, destination, 'Current')
  end

  def download_baseline(destination=Dir.pwd)
    destination = prep_path(destination)
    baseline_urls = get_images_urls_by_type('expectedAppOutput')
    download_images_from_url(baseline_urls, destination, 'Baseline')
  end

  def download_diffs(destination=Dir.pwd)
    destination = prep_path(destination)
    diff_urls = get_diff_urls
    download_images_from_url(diff_urls, destination, 'Diff')
  end

  def download_images(destination=Dir.pwd)
    download_baseline(destination)
    download_current(destination)
  end


  def download_images_from_url(urls, destination, file_signature)
    destination=(destination)
    step_names=get_step_names
    urls.each do |index, url|
      if url!=nil
        FileUtils.mkdir_p(destination) unless File.exist?(destination)
        File.open("#{destination}/#{step_names[index].gsub(/[\u0080-\u00ff]/, '')}_step_#{index+1}_#{file_signature}.png", 'wb') do |fo|
          response = send_long_request('GET', url)
          fo.write response.body
        end
      else
        print("No #{file_signature} image in step #{index+1}\n" )
      end
    end
  end

  def prep_path(path)
    path=(path+@path_prefix).gsub(/[\u0080-\u00ff]/, '')
    dirname = File.dirname(path)
    unless File.directory?(dirname)
      FileUtils.mkdir_p(dirname)
    end

    path
  end

  def get_diff_urls
    diff_template = "#{@server_url}/api/sessions/batches/#{@batch_id}/#{@session_id}/steps/%s/diff"
    diff_urls = Hash.new
    (0..@step_result.size - 1).each { |i|
      if @step_result[i] == @@result_status[:unresolved]
        diff_urls[i] = diff_template % [i + 1]
      else
        diff_urls[i] = nil
      end
    }

    diff_urls
  end

  def send_long_request(request_type, url)
    request = create_request(request_type, url)
    response = send_request(request)
    long_request_check_status(response)
  end

  def create_request(request_type, url)
    request = {}
    current_date = Time.now.httpdate
    headers = {
        "Eyes-Expect" => "202+location",
        "Eyes-Date" => current_date
    }
    request["headers"] = headers
    request["url"] = url
    request["request_type"] = request_type
    request
  end

  def send_request(request, retry_val = 1, delay_before_retry = false)
    @counter += 1
    request_id = @counter.to_s+"--"+SecureRandom.uuid.to_s

    headers = request["headers"]
    request_type = request["request_type"]
    url = request["url"]
    url = url + "?apiKey=" + @view_key

    headers["x-applitools-eyes-client-request-id"] = request_id

    begin
        if request_type == 'GET'
          response = Faraday.get(
              url,
              headers: headers,
          )
        elsif request_type == 'POST'
            response = Faraday.post(
                url,
                headers: headers
            )
        elsif request_type == 'DELETE'
            response = Faraday.delete(
                url,
                headers: headers
            )
        else
          raise "Not a valid request type"
        end
        response
    end
    rescue => error
      puts "Error: "+error.to_s
      if retry_val > 0
          if delay_before_retry
              sleep(@retry_request_interval)
              send_request(request, retry_val-1, delay_before_retry)
          end
          send_request(request, retry_val-1, delay_before_retry)
      end
      raise "Error: "+error.to_s
  end

  def long_request_check_status(response)
    status = response.status
    case status
    # OK
    when 200
      return response
    # Accepted
    when 202
      url = response.headers["location"]
      request = create_request('GET', url)
      request_response = long_request_loop(request, @long_request_delay)
      long_request_check_status(request_response)

    # Created
    when 201
      url = response.headers["location"]
      request = create_request('DELETE', url)
      send_request(request)

    # Gone
    when 410
        raise "The server task has gone"
    else
        raise "Unknown error during long request: " + status.to_s

    end
  end

  def long_request_loop(request, delay)
    delay = [@max_long_request_delay, (delay * @long_request_delay_multiplicative_factor).floor].min
    puts "Still running.. Retrying in "+delay.to_s+" s"
    sleep(delay)

    response = send_request(request)

    if response.status != 200
        response
    end
    long_request_loop(request, delay)
  end


  private :get_diff_urls, :download_images_from_url, :get_images_urls_by_type, :get_batch_id, :get_session_id, :prep_path, :read_test_data


end