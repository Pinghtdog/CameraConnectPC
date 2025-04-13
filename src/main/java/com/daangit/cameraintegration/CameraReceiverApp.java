package com.daangit.cameraintegration;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.embed.swing.SwingFXUtils; // Import the correct class

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;

public class CameraReceiverApp extends Application {

    private static final int PORT = 12345; // Must match Android app port
    private Label ipAddressLabel;
    private ImageView imageView;
    private Label statusLabel;
    private ServerSocket serverSocket;
    private volatile boolean isRunning = true;
    private Thread serverThread;

    @Override
    public void start(Stage primaryStage) {
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(640); // Match expected aspect ratio/size
        imageView.setFitHeight(480);

        statusLabel = new Label("Status: Initializing...");
        statusLabel.setTextFill(Color.BLACK);

        ipAddressLabel = new Label("IP: Detecting..."); // <-- Initialize Label
        ipAddressLabel.setTextFill(Color.BLUE);

        BorderPane root = new BorderPane();
        root.setCenter(imageView);
        root.setBottom(statusLabel);

        BorderPane bottomPane = new BorderPane();
        bottomPane.setLeft(statusLabel);
        bottomPane.setRight(ipAddressLabel); // <-- Add IP Label to layout
        root.setBottom(bottomPane);
        displayIpAddress(ipAddressLabel);

        Scene scene = new Scene(root, 660, 560); // Adjusted size for label

        primaryStage.setTitle("Phone Camera Receiver (TCP)");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(event -> stopServer()); // Crucial for cleanup
        primaryStage.show();

        startServer();
    }

    private void displayIpAddress(Label label) {
        // Getting local network info is usually fast, but using runLater ensures
        // we don't block the UI thread if something unexpected happens.
        Platform.runLater(() -> {
            String ipInfo = "IP: " + findRelevantLocalIpAddress();
            label.setText(ipInfo);
        });
    }

    // Method to find suitable local IP addresses
    private String findRelevantLocalIpAddress() {
        List<String> suitableIpList = new ArrayList<>();
        try {
            // Get all network interfaces
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                // Filter out interfaces that are down, loopback, or virtual
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }

                // Get all IP addresses for this interface
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();

                    // Filter for IPv4 addresses that are "site-local"
                    // (Common private ranges like 192.168.x.x, 10.x.x.x, 172.16.x.x-172.31.x.x)
                    // and not loopback (though covered by interface check mostly)
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        suitableIpList.add(addr.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Error getting network interfaces: " + e.getMessage());
            return "Error";
        }

        if (suitableIpList.isEmpty()) {
            // Fallback if no site-local IPv4 found (might happen in odd network configs)
            try {
                InetAddress localhost = InetAddress.getLocalHost();
                // Check if even this fallback is site-local before returning
                if (localhost.isSiteLocalAddress()){
                    return localhost.getHostAddress() + " (Fallback)";
                } else {
                    // Avoid showing public IP or 127.0.0.1 directly usually
                    return "Not Found (Check Network)";
                }

            } catch (Exception e) {
                return "Not Found";
            }

        } else {
            // Join results if multiple suitable IPs are found (e.g., Ethernet and Wi-Fi)
            return String.join(", ", suitableIpList);
        }
    }


    private void updateStatus(String message, boolean isError) {
        Platform.runLater(() -> {
            statusLabel.setText("Status: " + message);
            statusLabel.setTextFill(isError ? Color.RED : Color.BLACK);
        });
    }

    private void startServer() {
        updateStatus("Starting server on port " + PORT + "...", false);
        // Create and start the server thread
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                updateStatus("Waiting for connection on port " + PORT, false);

                while (isRunning) {
                    Socket clientSocket = null; // Declare outside try-with-resources to check in finally
                    try {
                        clientSocket = serverSocket.accept(); // Blocks until a client connects
                        updateStatus("Client connected: " + clientSocket.getInetAddress(), false);
                        clientSocket.setSoTimeout(10000); // Read timeout (milliseconds) - adjust as needed

                        // Use try-with-resources for the DataInputStream
                        try (DataInputStream inputStream = new DataInputStream(clientSocket.getInputStream())) {
                            while (isRunning && clientSocket.isConnected() && !clientSocket.isClosed()) {
                                // Protocol: Read size (int) first, then the JPEG bytes
                                int size = inputStream.readInt(); // Blocks until 4 bytes are read or timeout/error

                                if (size > 0 && size < 20 * 1024 * 1024) { // Sanity check size (e.g., < 20MB)
                                    byte[] imageBytes = new byte[size];
                                    inputStream.readFully(imageBytes); // Blocks until 'size' bytes are read

                                    // Decode image bytes (offload if it becomes slow?)
                                    ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
                                    BufferedImage bufferedImage = ImageIO.read(bais);

                                    if (bufferedImage != null) {
                                        // Convert to JavaFX Image
                                        Image fxImage = SwingFXUtils.toFXImage(bufferedImage, null);

                                        // Update UI on JavaFX Application Thread
                                        Platform.runLater(() -> {
                                            imageView.setImage(fxImage);
                                            // Optionally update status for received frame? Might be too noisy.
                                            // updateStatus("Received frame: " + size + " bytes", false);
                                        });
                                    } else {
                                        System.err.println("Failed to decode image bytes (size: " + size + ")");
                                        updateStatus("Failed to decode frame (size: " + size + ")", true);
                                    }
                                } else {
                                    if (size <= 0) {
                                        System.err.println("Received invalid size: " + size + ". Closing connection.");
                                        updateStatus("Received invalid frame size: " + size, true);
                                    } else {
                                        System.err.println("Received abnormally large frame size: " + size + ". Closing connection.");
                                        updateStatus("Received large frame size: " + size, true);
                                    }
                                    break; // Exit inner loop on bad size
                                }
                            } // End of inner while loop (reading frames)
                        } // DataInputStream is auto-closed here

                    } catch (SocketTimeoutException e) {
                        System.out.println("Read timed out waiting for frame size/data. Client might be slow or disconnected.");
                        updateStatus("Read timed out", true);
                        // Continue waiting in the accept loop for reconnection or next frame if still connected
                        // If the client is truly gone, the next readInt() or accept() will likely fail.
                        // If clientSocket is still valid, maybe just continue the inner loop?
                        // But often timeout means client issue, so breaking inner loop is safer.
                        if (clientSocket != null && !clientSocket.isClosed()){
                            // Continue inner loop - maybe temporary network issue
                        } else {
                            // Assume client is gone
                        }


                    } catch (SocketException e) {
                        if (isRunning) { // Ignore if we are shutting down
                            System.err.println("SocketException (Client likely disconnected abruptly): " + e.getMessage());
                            updateStatus("Client disconnected abruptly", true);
                        }
                    } catch (IOException e) {
                        if (isRunning) { // Ignore errors if we are stopping the server
                            System.err.println("IOException during client handling: " + e.getMessage());
                            updateStatus("Communication Error: " + e.getMessage(), true);
                            // This often means the client closed the connection or there's a network issue
                        }
                    } finally {
                        if (clientSocket != null) {
                            try {
                                clientSocket.close(); // Ensure client socket is closed
                            } catch (IOException ex) { /* ignore */ }
                        }
                        if(isRunning) { // Don't show waiting if we're stopping
                            updateStatus("Client disconnected. Waiting for new connection...", false);
                        }
                    }
                } // End of outer while loop (accepting connections)

            } catch (SocketException e) {
                if(isRunning) { // Ignore if stopServer() closed the socket
                    System.err.println("ServerSocketException: " + e.getMessage());
                    updateStatus("SERVER ERROR: " + e.getMessage(), true);
                }
            } catch (IOException e) {
                if (isRunning) {
                    System.err.println("Could not start server on port " + PORT + ": " + e.getMessage());
                    updateStatus("SERVER FAILED TO START: " + e.getMessage(), true);
                    e.printStackTrace();
                }
            } finally {
                // Final cleanup ensuring server socket is closed if the thread exits unexpectedly
                stopServerInternal();
                updateStatus("Server stopped.", false);
                System.out.println("Server thread finished.");
            }
        });

        serverThread.setDaemon(true); // Allow JVM exit even if this thread is running
        serverThread.start();
    }

    // Called when the application window is closed
    private void stopServer() {
        System.out.println("Stopping server...");
        isRunning = false; // Signal the server thread to stop looping
        stopServerInternal(); // Close the socket to interrupt accept()

        // Optionally wait for the server thread to finish
        if (serverThread != null) {
            try {
                serverThread.join(1000); // Wait max 1 second for thread to die
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("StopServer method finished.");
    }

    // Internal method to close the socket safely
    private synchronized void stopServerInternal() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
                System.out.println("ServerSocket closed.");
            } catch (IOException e) {
                System.err.println("Error closing server socket: " + e.getMessage());
            } finally {
                serverSocket = null; // Allow garbage collection
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}