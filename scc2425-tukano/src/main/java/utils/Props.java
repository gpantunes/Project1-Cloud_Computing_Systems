package utils;

import java.util.Arrays;

public class Props {

    public static void load(String[] keyValuePairs) {
        System.out.println(Arrays.asList(keyValuePairs));
        for (var pair : keyValuePairs) {
            var parts = pair.split("=");
            if (parts.length == 2) {
                // Handle Azure-specific properties
                if (parts[0].startsWith("AZURE_")) {
                    // Additional validation or processing for Azure properties can be added here
                }
                System.setProperty(parts[0], parts[1]);
            }
        }
    }
}
