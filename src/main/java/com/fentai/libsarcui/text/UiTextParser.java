package com.fentai.libsarcui.text;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;
import java.util.regex.Pattern;

public final class UiTextParser
{
    private static final Pattern IMAGE = Pattern.compile(
            "<image:([a-z0-9_.-]+:[a-z0-9_./-]+)(?::(\\d+):(\\d+))?>");
    private final ImageResolver images;

    public UiTextParser(ImageResolver images)
    {
        this.images = images;
    }

    public Component parse(String text, Key font, List<String> warnings)
    {
        // Replace on the parsed tree to preserve surrounding legacy colors and styles.
        Component component = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(text == null ? "" : text.replace('§', '&')).font(font);
        return component.replaceText(TextReplacementConfig.builder().match(IMAGE)
                .replacement((match, builder) ->
                {
                    try
                    {
                        int row = match.group(2) == null ? 0 : Integer.parseInt(match.group(2));
                        int column = match.group(3) == null ? 0 : Integer.parseInt(match.group(3));
                        // Bitmap artwork uses white to retain its original colors, regardless of text color.
                        return images.resolve(match.group(1), row, column).color(NamedTextColor.WHITE);
                    }
                    catch (IllegalArgumentException exception)
                    {
                        warnings.add(exception.getMessage());
                        return builder.build();
                    }
                }).build());
    }

    @FunctionalInterface
    public interface ImageResolver
    {
        Component resolve(String id, int row, int column);
    }
}
