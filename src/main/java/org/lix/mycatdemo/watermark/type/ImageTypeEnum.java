package org.lix.mycatdemo.watermark.type;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.MediaType;

@Getter
@AllArgsConstructor
public enum ImageTypeEnum {

    JPG("jpg", MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_JPEG),
    JPEG("jpg", "image/jpeg", MediaType.IMAGE_JPEG),
    PNG("png", MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_PNG),
    GIF("gif", MediaType.IMAGE_GIF_VALUE, MediaType.IMAGE_GIF);

    private String extension;

    private String mediaTypeString;

    private MediaType mediaType;

    public static ImageTypeEnum of(String extension) {
        for (ImageTypeEnum i : ImageTypeEnum.values()) {
            if (i.extension.equals(extension)) {
                return i;
            }
        }
        return JPG;
    }
}
