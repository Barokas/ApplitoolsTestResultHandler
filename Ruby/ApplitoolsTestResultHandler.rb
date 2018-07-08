require 'httparty'
require 'json'
require 'fileutils'
require 'open-uri'



class ApplitoolsTestResultHandler
  @@ResultStatus = {pass:"PASSED",failed:"FAILED",new:"NEW",miss:"MISSING"}

  def initialize(testResult, view_key)
    @Testresult = testResult
    @Viewkey=view_key
    @server_URL = get_server_url()
    @session_ID= get_session_id()
    @batch_ID = get_batch_id()
    @test_data=read_test_data()
    @stepResult=getStepResults()
    set_path_prefix_structure('')
  end

# path_temp= /#{testName}/#{AppName}/#{viewport}/#{hostingOS}/#{hostingApp}/
  def set_path_prefix_structure(path_template)
    path=String.new(path_template)
    path=path.sub('#{testName}',get_test_name())
    path=path.sub('#{AppName}',get_app_name())
    path=path.sub('#{viewport}',get_viewport_size())
    path=path.sub('#{hostingOS}',get_hosting_os())
    path=path.sub('#{hostingApp}',get_hosting_app())
    path+=@session_ID+'/'+@batch_ID+'/'

    @pathPrefix=path
  end

  def read_test_data
    url = @server_URL+"/api/sessions/batches/"+get_batch_id()+"/"+get_session_id()+"/?ApiKey="+@Viewkey+"&format=json"
    response = HTTParty.get(url)
    JSON.parse(response.body)
  end


  def get_server_url()
    @Testresult.url.split("/app/")[0]
  end

  def get_test_name()
    @test_data['startInfo']['scenarioName']
  end

  def get_app_name()
    @test_data['startInfo']['appName']
  end

  def get_viewport_size()
    @test_data['startInfo']['environment']['displaySize']['width'].to_s+'x'+@test_data['startInfo']['environment']['displaySize']['height'].to_s
  end

  def get_hosting_os()
    @test_data['startInfo']['environment']['os']
  end

  def get_hosting_app()
    @test_data['startInfo']['environment']['hostingApp']
  end

  def get_session_id()
    /batches\/\d+\/(?<sessionId>\d+)/.match(@Testresult.url)[1]
  end

  def get_batch_id()
    /batches\/(?<batchId>\d+)/.match(@Testresult.url)[1]
  end

  def calculate_step_results()
    @stepResult
  end

  def getStepResults()
    # url = @server_URL+"/api/sessions/batches/"+@batch_ID+"/"+@session_ID+"/?ApiKey="+@Viewkey+"&format=json"
    # response = HTTParty.get(url)
    # test_data = JSON.parse(response.body)
    expected=@test_data['expectedAppOutput']
    actual=@test_data['actualAppOutput']
    steps=[expected.size,actual.size].max
    retStepResults=Array.new(steps)

    for i in 0..steps-1
      if expected[i] == nil
        retStepResults[i]=@@ResultStatus[:new]
      elsif actual[i]==nil
        retStepResults[i]=@@ResultStatus[:miss]
      elsif actual[i]['isMatching']
        retStepResults[i]=@@ResultStatus[:pass]
      else
        retStepResults[i]=@@ResultStatus[:failed]
      end
    end

    retStepResults
  end


  def getStepNames()
    # url = @server_URL+"/api/sessions/batches/"+@batch_ID+"/"+@session_ID+"/?ApiKey="+@Viewkey+"&format=json"
    # response = HTTParty.get(url)
    # test_data = JSON.parse(response.body)
    expected=@test_data['expectedAppOutput']
    actual=@test_data['actualAppOutput']
    numSteps=[expected.size,actual.size].max
    retStepNames=Array.new(numSteps)

    for i in 0..(numSteps-1)
      if @stepResult[i]!=@@ResultStatus[:new]
        retStepNames[i]=expected[i]['tag']
      else
        retStepNames[i]=actual[i]['tag']
      end

    end
    retStepNames
  end

  def get_images_urls_by_type(image_type)
    # url_template = "#{@server_URL}/api/sessions/batches/#{@batch_ID}/#{@session_ID}?ApiKey=#{@Viewkey}&format=json"
    # response = HTTParty.get(url_template)
    # test_data = JSON.parse(response.body)
    image_data=@test_data[image_type]

    images_urls= Hash.new
    for index in 0 ... image_data.size

      if (image_type=='actualAppOutput' and @stepResult[index] !=@@ResultStatus[:miss]) or (image_type=='expectedAppOutput' and @stepResult[index]!=@@ResultStatus[:new])
        image_id=image_data[index]["image"]["id"]
        url="https://eyes.applitools.com/api/images/#{image_id}?ApiKey=#{@Viewkey}"
        images_urls[index] = url % [index]
      else
        images_urls[index] = nil
      end

    end

    images_urls
  end

  def download_current (destination=Dir.pwd)
    destination = prep_path(destination)
    current_urls = get_images_urls_by_type('actualAppOutput')
    download_images_from_URL(current_urls, destination,'Current')
  end

  def download_baseline(destination=Dir.pwd)
    destination = prep_path(destination)
    baseline_urls = get_images_urls_by_type('expectedAppOutput')
    download_images_from_URL(baseline_urls, destination,'Baseline')
  end

  def download_diffs(destination=Dir.pwd)
    destination = prep_path(destination)
    diff_urls = get_diff_urls()
    download_images_from_URL(diff_urls, destination,'Diff')
  end

  def download_images(destination=Dir.pwd)
    download_baseline(destination)
    download_current(destination)
  end




  def download_images_from_URL(urls, destination,file_signature)
    destination=(destination)
    stepNames=getStepNames()
    urls.each do |index, url|
      if url!=nil
        FileUtils.mkdir_p(destination) unless File.exist?(destination)
        # File.open("#{destination}/step_#{index+1}_#{file_signature}.png", 'wb') do |fo|
        File.open("#{destination}/#{stepNames[index].gsub(/[\u0080-\u00ff]/, '')}_step_#{index+1}_#{file_signature}.png", 'wb') do |fo|
          fo.write open(url).read
        end
      else
        print ("No #{file_signature} image in step #{index+1}\n" )
      end
    end
  end

  def prep_path(path)
    path=(path+@pathPrefix).gsub(/[\u0080-\u00ff]/, '')
    dirname = File.dirname(path)

    unless File.directory?(dirname)
      FileUtils.mkdir_p(dirname)
    end

    path
  end

  def get_diff_urls()
    diff_template = "#{@server_URL}/api/sessions/batches/#{@batch_ID}/#{@session_ID}/steps/%s/diff?ApiKey=#{@Viewkey}"
    diff_urls = Hash.new
    for i in 0..@stepResult.size-1
      if @stepResult[i]==@@ResultStatus[:failed]
        diff_urls[i] = diff_template % [i+1]
      else
        diff_urls[i] = nil
      end
    end

    diff_urls
  end

  private :get_diff_urls, :download_images_from_URL, :get_images_urls_by_type, :get_batch_id, :get_session_id, :prep_path, :read_test_data


end