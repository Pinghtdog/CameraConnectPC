package com.daangit.cameraintegration;

import javafx.application.Application;
import javafx.application.Platform; // Still needed for Platform.exit()
import javafx.fxml.FXMLLoader; // Import FXMLLoader
import javafx.scene.Parent; // Import Parent
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException; // Import IOException
import java.net.URL; // Import URL

public class CameraReceiverApp extends Application {

    private CameraReceiverController controller; // Reference to the controller instance

    @Override
    public void start(Stage primaryStage) {
        try {
            // Load the FXML file
            URL fxmlLocation = getClass().getResource("/com/daangit/cameraintegration/CameraReceiver.fxml"); // Adjust path if needed
            if (fxmlLocation == null) {
                System.err.println("Cannot find FXML file: CameraReceiver.fxml");
                Platform.exit(); // Exit if FXML is not found
                return;
            }
            FXMLLoader loader = new FXMLLoader(fxmlLocation);
            Parent root = loader.load(); // Load the FXML, which creates the UI and controller instance

            // Get the controller instance created by the FXMLLoader
            controller = loader.getController();

            Scene scene = new Scene(root); // Create scene from loaded UI

            primaryStage.setTitle("Phone Camera Receiver (TCP)");
            primaryStage.setScene(scene);

            // When the window is closed, call the controller's stopServer method
            primaryStage.setOnCloseRequest(event -> {
                System.out.println("Window close requested. Stopping server...");
                if (controller != null) {
                    controller.stopServer(); // Call the stop method on the controller
                }
                Platform.exit(); // Exit the JavaFX platform
            });

            primaryStage.show();

        } catch (IOException e) {
            System.err.println("Failed to load FXML or start application: " + e.getMessage());
            e.printStackTrace();
            Platform.exit(); // Exit if loading fails
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}