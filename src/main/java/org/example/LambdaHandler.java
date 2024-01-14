package org.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import java.io.IOException;
import java.util.Map;

public class LambdaHandler implements RequestHandler<Map<String,String>, String> {

    private final DynamoDbUpdater dynamoDbUpdater = new DynamoDbUpdater();

    public LambdaHandler() throws IOException {
    }

    @Override
    public String handleRequest(Map<String,String> event, Context context) {
        String mealType = event.get("mealType");
        context.getLogger().log("Starting capacity update for meal type: " + mealType);

        try {
            dynamoDbUpdater.updateCapacityDb(mealType);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return "Capacities updated for meal type: " + mealType;
    }
}
