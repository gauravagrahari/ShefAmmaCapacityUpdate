package org.example;

import java.util.Map;

public class OrderTrackEntity {
    private String pk; // Use "orderTrack" as a constant value
    private String sk; // Timestamp
    private Map<String, Integer> hostOrders; // Map of hostId and corresponding noOfOrders
    private String mealType;

    // Constructor, getters and setters
    public OrderTrackEntity(String pk, String sk, Map<String, Integer> hostOrders, String mealType) {
        this.pk = pk;
        this.sk = sk;
        this.hostOrders = hostOrders;
        this.mealType = mealType;
    }
}
