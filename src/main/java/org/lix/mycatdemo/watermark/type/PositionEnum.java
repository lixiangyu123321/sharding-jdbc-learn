package org.lix.mycatdemo.watermark.type;

public enum PositionEnum {

    RIGHT_BOTTOM("RIGHT_BOTTOM"),
    LEFT_BOTTOM("LEFT_BOTTOM"),
    RIGHT_TOP("RIGHT_TOP"),
    LEFT_TOP("LEFT_TOP"),
    CENTER("CENTER");

    private String position;

    PositionEnum(String position) {
        this.position = position;
    }
}
