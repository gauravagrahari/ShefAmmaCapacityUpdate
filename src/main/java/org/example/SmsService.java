package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class SmsService {

    private String API_BASE_URL;
    private String authKey;
    private String authToken;
    private String smsHeader;
    private ObjectMapper objectMapper;
    private String smsTemplate="\"Dear Shef Amma Team, Today, as part of our %s preparation service, you have to prepare total of %s meals. We are committed to delivering delicious and nutritious meals. Warm regards, SHEFAMMA PRIVATE LIMITED\"";
    private static final Logger logger = LoggerFactory.getLogger(SmsService.class);

    public SmsService() {
        objectMapper = new ObjectMapper(); // Initialize objectMapper here
        try (InputStream input = SmsService.class.getClassLoader().getResourceAsStream("application.properties")) {
            Properties prop = new Properties();

            if (input == null) {
                System.out.println("Sorry, unable to find application.properties");
                return;
            }

            // Load a properties file from class path
            prop.load(input);

            // Get the property values
            this.API_BASE_URL = prop.getProperty("apiBaseUrl");
            this.authKey = prop.getProperty("authKey");
            this.authToken = prop.getProperty("authToken");
            this.smsHeader=prop.getProperty("smsHeader");

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    public SmsResponse sendSms(String number, int noOfMeals, String mealType) throws Exception {
        try {
            HttpClient client = HttpClient.newHttpClient();

            String authStr = authKey + ":" + authToken;
            String base64AuthStr = "Basic " + Base64.getEncoder().encodeToString(authStr.getBytes());

            // Determine meal type string
            String mealTypeString;
            switch (mealType) {
                case "b":
                    mealTypeString = "BREAKFAST";
                    break;
                case "l":
                    mealTypeString = "LUNCH";
                    break;
                case "d":
                    mealTypeString = "DINNER";
                    break;
                default:
                    throw new IllegalArgumentException("Invalid meal type: " + mealType);
            }

            String formattedSmsTemplate = String.format(smsTemplate, mealTypeString, noOfMeals);

            OutgoingSmsRequest outgoingRequest = new OutgoingSmsRequest(formattedSmsTemplate, "91" + number, smsHeader, "https://www.domainname.com/notifyurl", "POST", "API");
            String requestBody = objectMapper.writeValueAsString(outgoingRequest);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE_URL + authKey + "/SMSes/"))
                    .header("Authorization", base64AuthStr)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201 || response.statusCode() == 202) {
                SmsResponse smsResponse = objectMapper.readValue(response.body(), SmsResponse.class);
                if (smsResponse.isSuccess()) {
                    return smsResponse;
                } else {
                    throw new Exception("SMS sending failed: " + smsResponse.getMessage());
                }
            } else {
                // Handle non-successful response
                throw new Exception("Failed to send SMS: HTTP status code " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            // Log and rethrow the exception to be handled further up the call stack
            logger.error("Error occurred while sending SMS", e);
            throw e;
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OutgoingSmsRequest {
        @JsonProperty("Text")
        private String Text;

        @JsonProperty("Number")
        private String Number;

        @JsonProperty("SenderId")
        private String SenderId;

        @JsonProperty("DRNotifyUrl")
        private String DRNotifyUrl;

        @JsonProperty("DRNotifyHttpMethod")
        private String DRNotifyHttpMethod;

        @JsonProperty("Tool")
        private String Tool;

        public OutgoingSmsRequest(SmsRequest smsRequest) {
            this.Text = smsRequest.getText();
            this.Number = smsRequest.getNumber();
            this.SenderId = smsRequest.getSenderId();
            this.DRNotifyUrl = smsRequest.getDrnotifyUrl();
            this.DRNotifyHttpMethod = smsRequest.getDrnotifyHttpMethod();
            this.Tool = smsRequest.getTool();
        }
    }
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SmsRequest {
        private String text;
        private String number;
        private String senderId;
        private String drnotifyUrl;
        private String drnotifyHttpMethod;
        private String tool;
    }


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SmsResponse {
        @JsonProperty("ApiId")
        private String apiId; // Ensure the field names match the JSON property names

        @JsonProperty("Success")
        private boolean success; // This should be a boolean as per your API's response

        @JsonProperty("Message")
        private String message;

        @JsonProperty("MessageUUID")
        private String messageUUID;
    }

}
