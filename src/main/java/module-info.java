module com.daangit.cameraintegration {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;
    requires javafx.swing;
    requires com.google.zxing;
    requires com.google.zxing.javase;

    opens com.daangit.cameraintegration to javafx.fxml;
    exports com.daangit.cameraintegration;
}