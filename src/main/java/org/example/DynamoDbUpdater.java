package org.example;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class DynamoDbUpdater {
    private static String DynamoDBTableName;
    private final AmazonDynamoDB dynamoDb;
    private static final Logger logger = LoggerFactory.getLogger(DynamoDbUpdater.class);
    private static final int BATCH_SIZE = 25;
    static {
        Properties properties = new Properties();
        try (InputStream input = DynamoDbUpdater.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                throw new FileNotFoundException("application.properties file not found in classpath");
            }
            properties.load(input);
            DynamoDBTableName = properties.getProperty("tableName");
        } catch (IOException e) {
            logger.error("Error loading properties", e);
            throw new RuntimeException("Failed to load properties", e);
        }
    }

    public DynamoDbUpdater() {
        Properties properties = new Properties();
        try (InputStream input = DynamoDbUpdater.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                throw new FileNotFoundException("application.properties file not found in classpath");
            }
            properties.load(input);
            String region = properties.getProperty("region");

            this.dynamoDb = AmazonDynamoDBClientBuilder.standard()
                    .withRegion(region)
                    .build();
        } catch (IOException e) {
            logger.error("Error loading properties", e);
            throw new RuntimeException("Failed to load properties", e);
        }
    }
    private void executeBatchUpdate(List<WriteRequest> writeRequests, String mealType) {
        Map<String, List<WriteRequest>> requestItems = new HashMap<>();
        requestItems.put(DynamoDBTableName, writeRequests);

        BatchWriteItemRequest batchWriteItemRequest = new BatchWriteItemRequest().withRequestItems(requestItems);
        BatchWriteItemResult result = dynamoDb.batchWriteItem(batchWriteItemRequest);
        // Check for unprocessed items
        if (!result.getUnprocessedItems().isEmpty()) {
            logger.error("There are unprocessed items: {}", result.getUnprocessedItems());
            // Handle unprocessed items as needed
        }
    }
    public String updateCapacityDb(String mealType) throws Exception {
        try {
            List<Map<String, AttributeValue>> hostResults = queryHostsByMealType(mealType);
            List<WriteRequest> writeRequests = new ArrayList<>();
            SmsService smsService = new SmsService(); // Initialize SMS service
            Map<String, Integer> hostOrderCounts = new HashMap<>(); // To track orders per host

            for (Map<String, AttributeValue> item : hostResults) {
                String hostId = item.get("pk").getS();
                String phoneNumber = item.get("phone").getS();
                Map<String, AttributeValue> originalCapacityItem = getOriginalCapacityItem(item, mealType);
                int noOfOrders = calculateDifference(originalCapacityItem, mealType);
                hostOrderCounts.put(hostId, noOfOrders);

                boolean smsSent = smsService.sendSmsWithRetry(phoneNumber, noOfOrders, mealType);
                if (!smsSent) {
                    logger.error("Failed to send SMS for host: " + hostId);
                    // Handle SMS sending failure as needed
                }
                // Create the write request with the updated capacity item
                WriteRequest writeRequest = createBatchWriteRequest(item, mealType);
                if (writeRequest != null) {
                    writeRequests.add(writeRequest);
                    if (writeRequests.size() == BATCH_SIZE) {
                        executeBatchUpdate(writeRequests, mealType);
                        writeRequests.clear();
                    }
                }
            }

            if (!writeRequests.isEmpty()) {
                executeBatchUpdate(writeRequests, mealType);
            }

            // Record the order tracking information
            recordOrderTrackingInfo(hostOrderCounts, mealType);

            return "Capacities updated for meal type: " + mealType;

        } catch (Exception e) {
            logger.error("Error updating capacities: {}", e.getMessage(), e);
            throw e;
        }
    }
    private void recordOrderTrackingInfo(Map<String, Integer> hostOrderCounts, String mealType) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String trackId = "orderTrack#"+mealType; // Constant pk value for all tracking records

        // Prepare the attribute for host orders
        Map<String, AttributeValue> hostOrderAttributes = new HashMap<>();
        hostOrderCounts.forEach((hostId, orders) -> {
            hostOrderAttributes.put(hostId, new AttributeValue().withN(String.valueOf(orders)));
        });

        Map<String, AttributeValue> itemValues = new HashMap<>();
        itemValues.put("pk", new AttributeValue(trackId));
        itemValues.put("sk", new AttributeValue(timestamp));
        itemValues.put("hostOrders", new AttributeValue().withM(hostOrderAttributes)); // Using a map attribute
        itemValues.put("mealType", new AttributeValue(mealType));

        PutItemRequest putItemRequest = new PutItemRequest()
                .withTableName(DynamoDBTableName)
                .withItem(itemValues);

        dynamoDb.putItem(putItemRequest);
    }


    private WriteRequest createBatchWriteRequest(Map<String, AttributeValue> item, String mealType) {
        String capacityUUID = item.get("pk").getS().replace("host#", "capacity#");
        Map<String, AttributeValue> keyMap = new HashMap<>();
        keyMap.put("pk", new AttributeValue().withS(capacityUUID));
        keyMap.put("sk", new AttributeValue().withS("capacity"));

        GetItemRequest getItemRequest = new GetItemRequest().withTableName(DynamoDBTableName).withKey(keyMap);
        Map<String, AttributeValue> originalCapacityItem = dynamoDb.getItem(getItemRequest).getItem();
        logger.info("Original capacity item map: {}", originalCapacityItem);

        // Clone the original item for modification
        Map<String, AttributeValue> modifiedCapacityItem = new HashMap<>(originalCapacityItem);

        if (!originalCapacityItem.isEmpty()) {
            String attributeToUpdate = determineAttributeToUpdate(originalCapacityItem, mealType);
            if (attributeToUpdate != null) {
                String derivedKey = capitalizeFirstLetter(attributeToUpdate.replace("cur", ""));
                int curValue = Integer.parseInt(originalCapacityItem.get(attributeToUpdate).getS());
                int newValue = Integer.parseInt(originalCapacityItem.get(derivedKey).getS());

                if (curValue < newValue) {
                    modifiedCapacityItem.put(attributeToUpdate, new AttributeValue().withS(String.valueOf(newValue)));
                    return new WriteRequest().withPutRequest(new PutRequest().withItem(modifiedCapacityItem));
                } else {
                    return null; // No update needed
                }
            }
        }
        return null;
    }
    private Map<String, AttributeValue> getOriginalCapacityItem(Map<String, AttributeValue> item, String mealType) {
        String capacityUUID = item.get("pk").getS().replace("host#", "capacity#");
        Map<String, AttributeValue> keyMap = new HashMap<>();
        keyMap.put("pk", new AttributeValue().withS(capacityUUID));
        keyMap.put("sk", new AttributeValue().withS("capacity"));

        GetItemRequest getItemRequest = new GetItemRequest().withTableName(DynamoDBTableName).withKey(keyMap);
        return dynamoDb.getItem(getItemRequest).getItem();
    }


    private List<Map<String, AttributeValue>> queryHostsByMealType(String mealType) {
        Map<String, AttributeValue> eav = new HashMap<>();
        eav.put(":gpk", new AttributeValue().withS("h"));
        eav.put(":gsk", new AttributeValue().withS("host#"));
        eav.put(":mealTypeValue", new AttributeValue().withS(mealType));

        QueryRequest hostQueryRequest = new QueryRequest()
                .withTableName(DynamoDBTableName)
                .withIndexName("gsi1")
                .withKeyConditionExpression("gpk = :gpk AND begins_with(gsk, :gsk)")
                .withFilterExpression("contains(provMeals, :mealTypeValue)")
                .withExpressionAttributeValues(eav)
                .withProjectionExpression("pk, phone");

        return dynamoDb.query(hostQueryRequest).getItems();
    }
    private int calculateDifference(Map<String, AttributeValue> capacityItem, String mealType) {
        String currentAttribute = "cur" + mealType.toUpperCase() + "Cap";
        String maxAttribute = mealType.toLowerCase() + "Cap";

        if (!capacityItem.containsKey(currentAttribute) || !capacityItem.containsKey(maxAttribute)) {
            logger.error("Missing attribute in calculateDifference: currentAttribute={} or maxAttribute={}", currentAttribute, maxAttribute);
            return 0;
        }

        AttributeValue currentAttrValue = capacityItem.get(currentAttribute);
        AttributeValue maxAttrValue = capacityItem.get(maxAttribute);

        if (currentAttrValue == null || maxAttrValue == null) {
            logger.error("Null AttributeValue for currentAttribute={} or maxAttribute={}", currentAttribute, maxAttribute);
            return 0;
        }

        int currentValue = Integer.parseInt(currentAttrValue.getS());
        int maxValue = Integer.parseInt(maxAttrValue.getS());

        return maxValue - currentValue;
    }


    private String capitalizeFirstLetter(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.substring(0, 1).toLowerCase() + input.substring(1);
    }

    private String determineAttributeToUpdate(Map<String, AttributeValue> capacityItem, String mealType) {
        String curAttribute = "cur" + mealType.toUpperCase() + "Cap";
        String maxAttribute = mealType + "Cap";

        if (capacityItem.containsKey(curAttribute) && capacityItem.containsKey(maxAttribute)) {
            int curValue = Integer.parseInt(capacityItem.get(curAttribute).getS());
            int maxValue = Integer.parseInt(capacityItem.get(maxAttribute).getS());
            if (curValue < maxValue) {
                return curAttribute;
            }
        }
        return null;
    }
    public static void main(String[] args) throws Exception {
        DynamoDbUpdater updater = new DynamoDbUpdater();
        updater.updateCapacityDb("d");  // Example for breakfast
    }
}