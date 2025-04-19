package com.daangit.cameraintegration;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.embed.swing.SwingFXUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.ResourceBundle;
import java.util.stream.Collectors; // Added for stream

// ZXing imports
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;

public class CameraReceiverController implements Initializable {

    private static final int PORT = 12345; // Must match Android app port

    @FXML
    private ImageView imageView; // Camera feed view
    @FXML
    private ImageView qrCodeImageView; // QR Code view
    @FXML
    private Label statusLabel;
    @FXML
    private Label ipAddressLabel;

    private ServerSocket serverSocket;
    private volatile boolean isRunning = true;
    private Thread serverThread;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        System.out.println("Controller initialized");

        // Initial UI setup and state
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(640);
        imageView.setFitHeight(480);
        imageView.setVisible(false); // Initially hide camera view, show QR code

        updateStatus("Initializing...", false);

        // Find IP address and generate QR code
        String localIp = findRelevantLocalIpAddress();
        displayIpAddress(localIp); // Display the found IP address

        if (!"Not Found".equals(localIp) && !"Error".equals(localIp)) {
            generateAndDisplayQrCode(localIp, PORT);
            updateStatus("Scan the QR code on your phone.", false);
        } else {
            updateStatus("Could not determine local IP address.", true);
            qrCodeImageView.setImage(null); // Clear QR code view
        }

        startServer(); // Start the server thread
    }

    // Method to update status label safely on FX thread
    private void updateStatus(String message, boolean isError) {
        Platform.runLater(() -> {
            statusLabel.setText("Status: " + message);
            statusLabel.setTextFill(isError ? Color.RED : Color.BLACK);
        });
    }

    // Method to display IP address safely on FX thread
    private void displayIpAddress(String ipAddress) {
        Platform.runLater(() -> {
            ipAddressLabel.setText("IP: " + ipAddress + " (Port: " + PORT + ")");
        });
    }

    // Method to find suitable local IP addresses
    private String findRelevantLocalIpAddress() {
        List<String> suitableIpList = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();

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
            try {
                InetAddress localhost = InetAddress.getLocalHost();
                if (localhost.isSiteLocalAddress()){
                    return localhost.getHostAddress() + " (Fallback)";
                } else {
                    return "Not Found (Check Network)";
                }
            } catch (Exception e) {
                return "Not Found";
            }
        } else {
            // **Improvement:** If multiple IPs, you might want to let the user choose
            // or just pick the first suitable IPv4 found.
            // For this QR code, we'll just pick the first one found.
            return suitableIpList.get(0);
        }
    }

    // --- QR Code Generation Method ---
    private void generateAndDisplayQrCode(String ipAddress, int port) {
        String qrCodeData = ipAddress + ":" + port; // Encode IP and Port

        try {
            int width = 200; // Width and height of the QR code image
            int height = 200;
            // You can add hints for encoding if needed
            // Map<EncodeHintType, Object> hints = new HashMap<>();
            // hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            BitMatrix bitMatrix = new MultiFormatWriter().encode(
                    qrCodeData,           // The data to encode
                    BarcodeFormat.QR_CODE,// The barcode format (QR code)
                    width,                // Width of the matrix
                    height                // Height of the matrix
                    // , hints             // Optional encoding hints
            );

            // Convert BitMatrix to BufferedImage
            BufferedImage bufferedImage = MatrixToImageWriter.toBufferedImage(bitMatrix);

            // Convert BufferedImage to JavaFX Image on the FX Application Thread
            Platform.runLater(() -> {
                Image fxImage = SwingFXUtils.toFXImage(bufferedImage, null);
                qrCodeImageView.setImage(fxImage);
            });

        } catch (WriterException e) {
            System.err.println("Error generating QR code: " + e.getMessage());
            updateStatus("Error generating QR code.", true);
            Platform.runLater(() -> qrCodeImageView.setImage(null)); // Clear the image view on error
        } catch (Exception e) {
            System.err.println("Unexpected error generating QR code: " + e.getMessage());
            e.printStackTrace();
            updateStatus("Unexpected error generating QR code.", true);
            Platform.runLater(() -> qrCodeImageView.setImage(null)); // Clear the image view on error
        }
    }


    // Method to start the server thread (remains largely the same)
    private void startServer() {
        updateStatus("Starting server...", false); // Updated status message
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                updateStatus("Waiting for connection on port " + PORT, false); // Updated status message

                while (isRunning) {
                    Socket clientSocket = null;
                    try {
                        clientSocket = serverSocket.accept();
                        updateStatus("Client connected: " + clientSocket.getInetAddress(), false);

                        // --- Once a client connects, hide the QR code and show the camera feed ---
                        Platform.runLater(() -> {
                            qrCodeImageView.setVisible(false); // Hide QR code
                            imageView.setVisible(true);     // Show camera feed
                        });
                        // -----------------------------------------------------------------------

                        clientSocket.setSoTimeout(10000);

                        try (DataInputStream inputStream = new DataInputStream(clientSocket.getInputStream())) {
                            while (isRunning && clientSocket.isConnected() && !clientSocket.isClosed()) {
                                int size = inputStream.readInt();

                                if (size > 0 && size < 20 * 1024 * 1024) {
                                    byte[] imageBytes = new byte[size];
                                    inputStream.readFully(imageBytes);

                                    ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
                                    BufferedImage bufferedImage = ImageIO.read(bais);

                                    if (bufferedImage != null) {
                                        Image fxImage = SwingFXUtils.toFXImage(bufferedImage, null);
                                        Platform.runLater(() -> {
                                            imageView.setImage(fxImage);
                                        });
                                    } else {
                                        System.err.println("Failed to decode image bytes (size: " + size + ")");
                                        updateStatus("Failed to decode frame.", true); // Simpler status
                                    }
                                } else {
                                    if (size <= 0) {
                                        System.err.println("Received invalid size: " + size + ". Closing connection.");
                                        updateStatus("Invalid frame size: " + size, true); // Simpler status
                                    } else {
                                        System.err.println("Received abnormally large frame size: " + size + ". Closing connection.");
                                        updateStatus("Large frame size: " + size, true); // Simpler status
                                    }
                                    break;
                                }
                            }
                        }

                    } catch (SocketTimeoutException e) {
                        if(isRunning && (clientSocket == null || !clientSocket.isClosed())) {
                            System.out.println("Read timed out.");
                            updateStatus("Read timed out.", true);
                        }
                    } catch (SocketException e) {
                        if (isRunning && (clientSocket == null || !clientSocket.isClosed())) {
                            System.err.println("SocketException: " + e.getMessage());
                            updateStatus("Client disconnected.", true); // Simpler status
                        }
                    } catch (IOException e) {
                        if (isRunning) {
                            System.err.println("IOException: " + e.getMessage());
                            updateStatus("Communication error.", true); // Simpler status
                        }
                    } finally {
                        if (clientSocket != null) {
                            try {
                                clientSocket.close();
                            } catch (IOException ex) { /* ignore */ }
                        }
                        // Reset UI state when client disconnects or error occurs
                        if(isRunning) { // Only show waiting if the server is still meant to be running
                            Platform.runLater(() -> {
                                imageView.setImage(null); // Clear camera feed
                                imageView.setVisible(false); // Hide camera feed
                                qrCodeImageView.setVisible(true); // Show QR code again
                                String localIp = findRelevantLocalIpAddress();
                                generateAndDisplayQrCode(localIp, PORT); // Regenerate/show QR
                                updateStatus("Client disconnected. Scan QR code to reconnect.", false); // Updated status
                            });
                        }
                    }
                } // End of outer while

            } catch (SocketException e) {
                if (isRunning) {
                    System.err.println("ServerSocketException: " + e.getMessage());
                    updateStatus("SERVER ERROR: " + e.getMessage(), true);
                }
            } catch (IOException e) {
                if (isRunning) {
                    System.err.println("Could not start server on port " + PORT + ": " + e.getMessage());
                    updateStatus("SERVER FAILED TO START.", true); // Simpler status
                    e.printStackTrace();
                }
            } finally {
                stopServerInternal();
                updateStatus("Server stopped.", false);
                System.out.println("Server thread finished.");
            }
        });

        serverThread.setDaemon(true);
        serverThread.start();
    }

    // Method to signal server thread to stop and close socket (called from outside the controller)
    public void stopServer() {
        System.out.println("Stopping server...");
        isRunning = false;
        stopServerInternal();

        if (serverThread != null) {
            try {
                serverThread.join(1000);
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
                serverSocket = null;
            }
        }
    }
}