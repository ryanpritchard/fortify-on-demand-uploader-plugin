package org.jenkinsci.plugins.fodupload.controllers;

import com.fortify.fod.parser.BsiToken;
import com.google.gson.Gson;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.util.IOUtils;
import okhttp3.*;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.fodupload.FodApiConnection;
import org.jenkinsci.plugins.fodupload.Utils;
import org.jenkinsci.plugins.fodupload.models.JobModel;
import org.jenkinsci.plugins.fodupload.models.response.GenericErrorResponse;
import org.jenkinsci.plugins.fodupload.models.response.PostStartScanResponse;
import org.jenkinsci.plugins.fodupload.models.response.ReleaseAssessmentTypeDTO;
import sun.applet.Main;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Properties;

public class StaticScanController extends ControllerBase {

    private final static int EXPRESS_SCAN_PREFERENCE_ID = 2;
    private final static int EXPRESS_AUDIT_PREFERENCE_ID = 2;
    private final static int MAX_NOTES_LENGTH = 250;
    private final static int CHUNK_SIZE = 1024 * 1024;
    private PrintStream logger;

    /**
     * Constructor
     *
     * @param apiConnection apiConnection object with client info
     * @param logger        logger object to display to console
     */
    public StaticScanController(final FodApiConnection apiConnection, final PrintStream logger) {
        super(apiConnection);
        this.logger = logger;
    }

    /**
     * Begin a static scan on FoD
     *
     * @param uploadRequest zip file to upload
     * @param notes         notes
     * @return true if the scan succeeded
     */
    @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION", justification = "The intent of the catch-all is to make sure that the Jenkins user and logs show the plugin's problem in the build log.")
    public boolean startStaticScan(final JobModel uploadRequest, final String notes) {

        PostStartScanResponse scanStartedResponse = null;

        File uploadFile = uploadRequest.getPayload();
        try (FileInputStream fs = new FileInputStream(uploadFile)) {
            byte[] readByteArray = new byte[CHUNK_SIZE];
            byte[] sendByteArray;
            int fragmentNumber = 0;
            int byteCount;
            long offset = 0;

            if (apiConnection.getToken() == null)
                apiConnection.authenticate();

            logger.println("Getting Assessment");

            BsiToken token = uploadRequest.getBsiToken();

            String projectVersion;
            try (InputStream inputStream = this.getClass().getResourceAsStream("/application.properties")) {
                Properties props = new Properties();
                props.load(inputStream);
                projectVersion = props.getProperty("application.version", "Not Found");
            }

            HttpUrl.Builder builder = HttpUrl.parse(apiConnection.getApiUrl()).newBuilder()
                    .addPathSegments(String.format("/api/v3/releases/%d/static-scans/start-scan-advanced", token.getProjectVersionId()))
                    .addQueryParameter("bsiToken", uploadRequest.getBsiTokenOriginal())
                    .addQueryParameter("technologyStack", token.getTechnologyType())
                    .addQueryParameter("entitlementPreferenceType", uploadRequest.getEntitlementPreference())
                    .addQueryParameter("purchaseEntitlement", Boolean.toString(uploadRequest.isPurchaseEntitlements()))
                    .addQueryParameter("remdiationScanPreferenceType", uploadRequest.getRemediationScanPreferenceType())
                    .addQueryParameter("inProgressScanActionType", uploadRequest.getInProgressScanActionType())
                    .addQueryParameter("scanMethodType", "CICD")
                    .addQueryParameter("scanTool", "Jenkins")
                    .addQueryParameter("scanToolVersion", projectVersion != null ? projectVersion : "NotFound");


            if (!Utils.isNullOrEmpty(notes)) {
                String truncatedNotes = StringUtils.left(notes, MAX_NOTES_LENGTH);
                builder = builder.addQueryParameter("notes", truncatedNotes);
            }

            if (token.getTechnologyVersion() != null) {
                builder = builder.addQueryParameter("languageLevel", token.getTechnologyVersion());
            }

            // TODO: Come back and fix the request to set fragNo and offset query parameters
            String fragUrl = builder.build().toString();

            // Loop through chunks

            logger.println("TOTAL FILE SIZE = " + uploadFile.length());
            logger.println("CHUNK_SIZE = " + CHUNK_SIZE);

            while ((byteCount = fs.read(readByteArray)) != -1) {

                if (byteCount < CHUNK_SIZE) {
                    sendByteArray = Arrays.copyOf(readByteArray, byteCount);
                    fragmentNumber = -1;
                } else {
                    sendByteArray = readByteArray;
                }

                MediaType byteArray = MediaType.parse("application/octet-stream");
                Request request = new Request.Builder()
                        .addHeader("Authorization", "Bearer " + apiConnection.getToken())
                        .addHeader("Content-Type", "application/octet-stream")
                        .addHeader("Accept", "application/json")
                        // Add offsets
                        .url(fragUrl + "&fragNo=" + fragmentNumber++ + "&offset=" + offset)
                        .post(RequestBody.create(byteArray, sendByteArray))
                        .build();

                // Get the response
                Response response = apiConnection.getClient().newCall(request).execute();

                if (response.code() == HttpStatus.SC_FORBIDDEN) {  // got logged out during polling so log back in
                    // Re-authenticate
                    apiConnection.authenticate();

                    // if you had to reauthenticate here, would the loop and request not need to be resubmitted?
                    // possible continue?
                }

                offset += byteCount;

                if (fragmentNumber % 5 == 0) {
                    logger.println("Upload Status - Fragment No: " + fragmentNumber + ", Bytes sent: " + offset
                            + " (Response: " + response.code() + ")");
                }

                if (response.code() != 202) {
                    String responseJsonStr = IOUtils.toString(response.body().byteStream(), "utf-8");

                    Gson gson = new Gson();
                    // final response has 200, try to deserialize it
                    if (response.code() == 200) {

                        scanStartedResponse = gson.fromJson(responseJsonStr, PostStartScanResponse.class);
                        logger.println("Scan " + scanStartedResponse.getScanId() + " uploaded successfully. Total bytes sent: " + offset);
                        return true;

                    } else if (!response.isSuccessful()) { // There was an error along the lines of 'another scan in progress' or something

                        logger.println("An error occurred during the upload.");
                        GenericErrorResponse errors = gson.fromJson(responseJsonStr, GenericErrorResponse.class);
                        if (errors != null)
                            logger.println("Package upload failed for the following reasons: " + errors.toString());
                        return false; // if there is an error, get out of loop and mark build unstable
                    }
                }
                response.body().close();

            } // end while

        } catch (Exception e) {
            e.printStackTrace(logger);
            return false;
        }

        return false;
    }
}
