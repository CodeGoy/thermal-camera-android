package com.example.thermalcamera;

/**
 * Holds thermal frame data received from the camera.
 * The image data is grayscale (256x192), and temperature values are in Celsius.
 */
public class ThermalData {
    /** Width of the thermal image */
    public static final int WIDTH = 256;
    /** Height of the thermal image */
    public static final int HEIGHT = 192;

    /** Grayscale image data (Y channel from YUYV) */
    public final byte[] imageData;

    /** Temperature at the center of the frame */
    public final float centerTemp;

    /** Minimum temperature in the frame */
    public final float minTemp;

    /** Maximum temperature in the frame */
    public final float maxTemp;

    /** Average temperature across the frame */
    public final float avgTemp;

    /** Row position of minimum temperature (0-191) */
    public final int minRow;

    /** Column position of minimum temperature (0-255) */
    public final int minCol;

    /** Row position of maximum temperature (0-191) */
    public final int maxRow;

    /** Column position of maximum temperature (0-255) */
    public final int maxCol;

    public ThermalData(byte[] imageData,
                       float centerTemp, float minTemp, float maxTemp, float avgTemp,
                       int minRow, int minCol, int maxRow, int maxCol) {
        this.imageData = imageData;
        this.centerTemp = centerTemp;
        this.minTemp = minTemp;
        this.maxTemp = maxTemp;
        this.avgTemp = avgTemp;
        this.minRow = minRow;
        this.minCol = minCol;
        this.maxRow = maxRow;
        this.maxCol = maxCol;
    }
}
