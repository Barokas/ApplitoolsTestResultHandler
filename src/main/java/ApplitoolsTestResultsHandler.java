import com.applitools.eyes.TestResults;
import com.sun.glass.ui.Size;
import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.net.ssl.HttpsURLConnection;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ApplitoolsTestResultsHandler {

    private static final String VERSION = "1.1.0";
    private static final String STEP_RESULT_API_FORMAT = "/api/sessions/batches/%s/%s/?ApiKey=%s&format=json";
    private static final String RESULT_REGEX = "(?<serverURL>^.+)\\/app\\/batches\\/(?<batchId>\\d+)\\/(?<sessionId>\\d+).*$";
    private static final String IMAGE_TMPL = "%s/step %s %s-%s.png";
    private static final int DEFAULT_TIME_BETWEEN_FRAMES = 500;
    private static final String DiffsUrlTemplate = "%s/api/sessions/batches/%s/%s/steps/%s/diff?ApiKey=%s";


    private String applitolsViewKey;
    private String serverURL;
    private String batchID;
    private String sessionID;
    private TestResults testResults;
    private String[] stepsNames;
    private ResultStatus[] stepsState;
    private JSONObject testData;
    private String prefix="";

    private String preparePath(String Path){
        Path+="/"+prefix;
        if (!Path.contains("/"+batchID+"/"+sessionID)) {
            Path = Path + "/" + batchID + "/" + sessionID;
            File folder = new File(Path);
            if (!folder.exists()) folder.mkdirs();
        }
        return Path;

    }

    public ApplitoolsTestResultsHandler(TestResults testResults, String viewkey) throws Exception {
        this.applitolsViewKey = viewkey;
        this.testResults = testResults;
        Pattern pattern = Pattern.compile(RESULT_REGEX);
        Matcher matcher = pattern.matcher(testResults.getUrl());
        if (!matcher.find()) throw new Exception("Unexpected result URL - Not parsable");
        this.batchID = matcher.group("batchId");
        this.sessionID = matcher.group("sessionId");
        this.serverURL = matcher.group("serverURL");

        String url = String.format(serverURL + STEP_RESULT_API_FORMAT, this.batchID, this.sessionID, this.applitolsViewKey);
        String json = readJsonStringFromUrl(url);
        this.testData = new JSONObject(json);

        this.stepsNames= calculateStepsNames();
        this.stepsState= prepareStepResults();

    }

    private URL[] getDiffUrls() {
        URL[] urls = new URL[stepsState.length];
//        String urlTemplate = serverURL + "/api/sessions/%s/steps/%s/diff?ApiKey=%s";
//        String urlTemplate = serverURL + "/api/sessions/batches/%s/%s/steps/%s/diff?ApiKey=%s";

        for (int step = 0; step < this.testResults.getSteps(); ++step) {
            if (stepsState[step] == ResultStatus.FAILED) {
                try {
                    urls[step] = new URL(String.format(DiffsUrlTemplate,this.serverURL, this.batchID,this.sessionID, step + 1, this.applitolsViewKey));
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                }
            } else urls[step] = null;
        }
        return urls;
    }

    public ResultStatus[] calculateStepResults(){
        if (stepsState==null) try {
            stepsState = prepareStepResults();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return stepsState;
    }


    public String getLinkToStep(int step){

        String link = testResults.getUrl().replaceAll("batches","sessions");
        StringBuffer buf = new StringBuffer(link);
        int index =link.indexOf("?accountId=");
        return (buf.insert(index,"/steps/"+step).toString());
    }

    private ResultStatus[] prepareStepResults() throws Exception {
        JSONArray expected = this.testData.getJSONArray("expectedAppOutput");
        JSONArray actual = this.testData.getJSONArray("actualAppOutput");

        int steps = Math.max(expected.length(), actual.length());
        ResultStatus[] retStepResults = new ResultStatus[steps];

        for (int i = 0; i < steps; i++) {

            if (expected.get(i) == JSONObject.NULL) {
                retStepResults[i] = ResultStatus.NEW;
            } else if (actual.get(i) == JSONObject.NULL) {
                retStepResults[i] = ResultStatus.MISSING;
            } else if (actual.getJSONObject(i).getBoolean("isMatching")) {
                retStepResults[i] = ResultStatus.PASSED;
            } else {
                retStepResults[i] = ResultStatus.FAILED;
            }

        }
        return retStepResults;
    }

    public String[] getStepsNames(){
        return this.stepsNames;
    }

    private String[] calculateStepsNames() throws Exception {
        ResultStatus[] stepResults= calculateStepResults();
        JSONArray expected = this.testData.getJSONArray("expectedAppOutput");
        JSONArray actual = this.testData.getJSONArray("actualAppOutput");
        int steps= expected.length();
        String[] StepsNames = new String[steps];

        for (int i = 0; i < steps; i++) {
            if (stepResults[i]!= ResultStatus.NEW) {
                StepsNames[i] = expected.getJSONObject(i).optString("tag");
            }
            else {
                StepsNames[i] = actual.getJSONObject(i).optString("tag");
            }
        }
        return StepsNames;
    }

    private String readJsonStringFromUrl(String url) throws Exception {
        HttpsURLConnection.setDefaultSSLSocketFactory(new sun.security.ssl.SSLSocketFactoryImpl());
        InputStream is = new URL(url).openStream();
        try {
            BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
            return readAll(rd);
        } finally {
            is.close();
        }
    }

    private String readAll(Reader rd) throws IOException {
        StringBuilder sb = new StringBuilder();
        int cp;
        while ((cp = rd.read()) != -1) {
            sb.append((char) cp);
        }
        return sb.toString();
    }

    public void downloadDiffs(String path) throws Exception {
        saveImagesInFolder(preparePath(path),"Diff", getDiffUrls());
    }

    public void downloadBaselineImages(String path) throws IOException, InterruptedException {
        saveImagesInFolder(preparePath(path), "Baseline",getBaselineImagesURLS());
    }

    public void downloadCurrentImages(String path) throws IOException, InterruptedException {
        saveImagesInFolder(preparePath(path), "Current",getCurrentImagesURLS());
    }

    public void downloadImages(String path) throws Exception {
        downloadBaselineImages(path);
        downloadCurrentImages(path);
    }

    private void saveImagesInFolder(String path, String imageType, URL[] imageURLS) throws InterruptedException, IOException, JSONException {
        for (int i = 0; i < imageURLS.length; i++) {
            if (imageURLS[i] == null) {
                System.out.println("No " + imageType + " image in step " +(i+1)+": "+ stepsNames[i]);
            } else {
                FileUtils.copyURLToFile(imageURLS[i], new File(String.format(IMAGE_TMPL, path, (i+1),stepsNames[i], imageType)));
            }
        }
    }

    private URL[] getDownloadImagesURLSByType(String imageType){
        String[] imageIds = getImagesUIDs(this.sessionID, this.batchID, imageType);
        URL[] URLS = new URL[calculateStepResults().length];
        for (int i = 0; i < imageIds.length; i++) {
            if (imageIds[i] == null) {
                URLS[i]=null;
            } else try {
                URLS[i] = new URL(String.format("%s/api/images/%s?apiKey=%s", this.serverURL, imageIds[i], this.applitolsViewKey, this.applitolsViewKey));
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }
        return URLS;
    }

    private URL[] getCurrentImagesURLS(){
        return getDownloadImagesURLSByType("Current");
    }

    private URL[] getBaselineImagesURLS(){
        return getDownloadImagesURLSByType("Baseline");
    }

    private String[] getImagesUIDs(String sessionId, String batchId, String imageType) {
        String sessionInfo = null;
        try {
            sessionInfo = getSessionInfo(sessionId, batchId);
        } catch (IOException e) {
            e.printStackTrace();
        }
        JSONObject obj = new JSONObject(sessionInfo);
        if (imageType == "Baseline") {
            return getImagesUIDs(obj.getJSONArray("expectedAppOutput"));
        } else if (imageType == "Current") {
            return getImagesUIDs(obj.getJSONArray("actualAppOutput"));
        }
        return null;
    }

    private String[] getImagesUIDs(JSONArray infoTable) {
        String[] retUIDs = new String[infoTable.length()];

        for (int i = 0; i < infoTable.length(); i++) {
            if (infoTable.isNull(i)) {
                retUIDs[i] = null;
            } else {
                JSONObject entry = infoTable.getJSONObject(i);
                JSONObject image = entry.getJSONObject("image");
                retUIDs[i] = (image.getString("id"));
            }
        }
        return retUIDs;
    }



    public void downloadAnimateGiff(String path){
        downloadAnimateGiff(path, DEFAULT_TIME_BETWEEN_FRAMES);
    }
    public void downloadAnimateGiff(String path, int timeBetweenFramesMS){

        if (testResults.getMismatches()+testResults.getMatches()>0) // only if the test isn't new and not all of his steps are missing
        {
            URL[] baselienImagesURL = getBaselineImagesURLS();
            URL[] currentImagesURL = getCurrentImagesURLS();
            URL[] diffImagesURL = getDiffUrls();

            for (int i = 0; i < stepsState.length; i++) {
                if (stepsState[i] == ResultStatus.FAILED) {
                    List<BufferedImage> list = new ArrayList<BufferedImage>();
                    try {
                        if (currentImagesURL[i]!=null)  list.add(ImageIO.read(currentImagesURL[i]));
                        if (baselienImagesURL[i]!=null) list.add(ImageIO.read(baselienImagesURL[i]));
                        if (diffImagesURL[i]!=null) list.add(ImageIO.read(diffImagesURL[i]));
                        String tempPath= preparePath(path) + "/"+(i+1)+" - AnimatedGiff.gif";
                        createAnimatedGif(list,new File(tempPath),timeBetweenFramesMS);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    System.out.println("No Animated GIf created for Step " + (i+1) + " "+ stepsNames[i] +" as it is " + stepsState[i]);
                }
            }
        }
    }

    private String getSessionInfo(String sessionId, String batchId) throws IOException {
        URL url = new URL(String.format("%s/api/sessions/batches/%s/%s?apiKey=%s&format=json", this.serverURL, batchId, sessionId, this.applitolsViewKey));
        InputStream stream = url.openStream();

        try {
            BufferedReader rd = new BufferedReader(new InputStreamReader(stream, Charset.forName("UTF-8")));
            return readAll(rd);
        } finally {
            stream.close();
        }
    }


    private static void createAnimatedGif(List<BufferedImage> images, File target, int timeBetweenFramesMS) throws IOException {
        ImageOutputStream output = new FileImageOutputStream(target);
        GifSequenceWriter writer = null;

        Size max = getMaxSize(images);

        try {
            for (BufferedImage image : images) {
                BufferedImage normalized = new BufferedImage(max.width, max.height, image.getType());
                normalized.getGraphics().drawImage(image, 0, 0, null);
                if (writer == null) writer = new GifSequenceWriter(output, image.getType(), timeBetweenFramesMS, true);
                writer.writeToSequence(normalized);
            }
        } finally {
            writer.close();
            output.close();
        }
    }
    private static Size getMaxSize(List<BufferedImage> images) {
        Size max = new Size(0, 0);
        for (BufferedImage image : images) {
            if (max.height < image.getHeight()) max.height = image.getHeight();
            if (max.width < image.getWidth()) max.width = image.getWidth();
        }
        return max;
    }


    private static class GifSequenceWriter {
        protected ImageWriter gifWriter;
        protected ImageWriteParam imageWriteParam;
        protected IIOMetadata imageMetaData;

        /**
         * Creates a new GifSequenceWriter
         *
         * @param outputStream the ImageOutputStream to be written to
         * @param imageType one of the imageTypes specified in BufferedImage
         * @param timeBetweenFramesMS the time between frames in miliseconds
         * @param loopContinuously wether the gif should loop repeatedly
         * @throws IIOException if no gif ImageWriters are found
         *
         * @author Elliot Kroo (elliot[at]kroo[dot]net)
         */
        public GifSequenceWriter(
                ImageOutputStream outputStream,
                int imageType,
                int timeBetweenFramesMS,
                boolean loopContinuously) throws IIOException, IOException {
            // my method to create a writer
            gifWriter = getWriter();
            imageWriteParam = gifWriter.getDefaultWriteParam();
            ImageTypeSpecifier imageTypeSpecifier =
                    ImageTypeSpecifier.createFromBufferedImageType(imageType);

            imageMetaData =
                    gifWriter.getDefaultImageMetadata(imageTypeSpecifier,
                            imageWriteParam);

            String metaFormatName = imageMetaData.getNativeMetadataFormatName();

            IIOMetadataNode root = (IIOMetadataNode)
                    imageMetaData.getAsTree(metaFormatName);

            IIOMetadataNode graphicsControlExtensionNode = getNode(
                    root,
                    "GraphicControlExtension");

            graphicsControlExtensionNode.setAttribute("disposalMethod", "none");
            graphicsControlExtensionNode.setAttribute("userInputFlag", "FALSE");
            graphicsControlExtensionNode.setAttribute(
                    "transparentColorFlag",
                    "FALSE");
            graphicsControlExtensionNode.setAttribute(
                    "delayTime",
                    Integer.toString(timeBetweenFramesMS / 10));
            graphicsControlExtensionNode.setAttribute(
                    "transparentColorIndex",
                    "0");

            IIOMetadataNode commentsNode = getNode(root, "CommentExtensions");
            commentsNode.setAttribute("CommentExtension", "Created by MAH");

            IIOMetadataNode appEntensionsNode = getNode(
                    root,
                    "ApplicationExtensions");

            IIOMetadataNode child = new IIOMetadataNode("ApplicationExtension");

            child.setAttribute("applicationID", "NETSCAPE");
            child.setAttribute("authenticationCode", "2.0");

            int loop = loopContinuously ? 0 : 1;

            child.setUserObject(new byte[]{ 0x1, (byte) (loop & 0xFF), (byte)
                    ((loop >> 8) & 0xFF)});
            appEntensionsNode.appendChild(child);

            imageMetaData.setFromTree(metaFormatName, root);

            gifWriter.setOutput(outputStream);

            gifWriter.prepareWriteSequence(null);
        }

        public void writeToSequence(RenderedImage img) throws IOException {
            gifWriter.writeToSequence(
                    new IIOImage(
                            img,
                            null,
                            imageMetaData),
                    imageWriteParam);
        }

        /**
         * Close this GifSequenceWriter object. This does not close the underlying
         * stream, just finishes off the GIF.
         */
        public void close() throws IOException {
            gifWriter.endWriteSequence();
        }

        /**
         * Returns the first available GIF ImageWriter using
         * ImageIO.getImageWritersBySuffix("gif").
         *
         * @return a GIF ImageWriter object
         * @throws IIOException if no GIF image writers are returned
         */
        private static ImageWriter getWriter() throws IIOException {
            Iterator<ImageWriter> iter = ImageIO.getImageWritersBySuffix("gif");
            if(!iter.hasNext()) {
                throw new IIOException("No GIF Image Writers Exist");
            } else {
                return iter.next();
            }
        }

        /**
         * Returns an existing child node, or creates and returns a new child node (if
         * the requested node does not exist).
         *
         * @param rootNode the <tt>IIOMetadataNode</tt> to search for the child node.
         * @param nodeName the name of the child node.
         *
         * @return the child node, if found or a new node created with the given name.
         */
        private static IIOMetadataNode getNode(
                IIOMetadataNode rootNode,
                String nodeName) {
            int nNodes = rootNode.getLength();
            for (int i = 0; i < nNodes; i++) {
                if (rootNode.item(i).getNodeName().compareToIgnoreCase(nodeName)
                        == 0) {
                    return((IIOMetadataNode) rootNode.item(i));
                }
            }
            IIOMetadataNode node = new IIOMetadataNode(nodeName);
            rootNode.appendChild(node);
            return(node);
        }

        /**
         public GifSequenceWriter(
         BufferedOutputStream outputStream,
         int imageType,
         int timeBetweenFramesMS,
         boolean loopContinuously) {

         */

        public static void main(String[] args) throws Exception {
            if (args.length > 1) {
                // grab the output image type from the first image in the sequence
                BufferedImage firstImage = ImageIO.read(new File(args[0]));

                // create a new BufferedOutputStream with the last argument
                ImageOutputStream output =
                        new FileImageOutputStream(new File(args[args.length - 1]));

                // create a gif sequence with the type of the first image, 1 second
                // between frames, which loops continuously
                GifSequenceWriter writer =
                        new GifSequenceWriter(output, firstImage.getType(), 1, false);

                // write out the first image to our sequence...
                writer.writeToSequence(firstImage);
                for(int i=1; i<args.length-1; i++) {
                    BufferedImage nextImage = ImageIO.read(new File(args[i]));
                    writer.writeToSequence(nextImage);
                }

                writer.close();
                output.close();
            } else {
                System.out.println(
                        "Usage: java GifSequenceWriter [list of gif files] [output file]");
            }
        }
    }

    public void SetPathPrefixStructure(String pathPrefix){
        pathPrefix= pathPrefix.replaceAll("TestName",this.getTestName());
        pathPrefix= pathPrefix.replaceAll("AppName",this.getAppName());
        pathPrefix= pathPrefix.replaceAll("viewport",this.getViewportSize());
        pathPrefix= pathPrefix.replaceAll("hostingOS",this.getHostingOS());
        pathPrefix= pathPrefix.replaceAll("hostingApp",this.getHostingApp());
        prefix = pathPrefix;
    }

    public String getTestName(){
        return this.testData.getJSONObject("startInfo").optString("scenarioIdOrName");
    }
    public String getAppName(){
        return this.testData.getJSONObject("startInfo").optString("appIdOrName");
    }

    public String getViewportSize() {
        return this.testData.getJSONObject("startInfo").getJSONObject("environment").getJSONObject("displaySize").optString("width").toString()+"x"+this.testData.getJSONObject("startInfo").getJSONObject("environment").getJSONObject("displaySize").optString("height").toString();
    }

    public String getHostingOS() {
        return this.testData.getJSONObject("startInfo").getJSONObject("environment").optString("os");

    }
    public String getHostingApp() {
        return this.testData.getJSONObject("startInfo").getJSONObject("environment").optString("hostingApp");

    }

}



