package com.fentai.libsarcui.text;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional bridge: CE shades Adventure, so read its glyph/font rather than casting its component. */
public final class CraftEngineImageResolver implements UiTextParser.ImageResolver
{
    private static final Pattern FONT = Pattern.compile("<font:([^>]+)>");
    private final Supplier<Plugin> craftEngine;

    public CraftEngineImageResolver(Supplier<Plugin> craftEngine)
    {
        this.craftEngine = craftEngine;
    }

    @Override
    public Component resolve(String id, int row, int column)
    {
        Plugin host = craftEngine.get();
        if (host == null || !host.isEnabled())
        {
            throw new IllegalArgumentException("CraftEngine 未启用，无法解析图片 " + id);
        }
        try
        {
            ClassLoader loader = host.getClass().getClassLoader();
            Class<?> keyClass = Class.forName("net.momirealms.craftengine.core.util.Key", true, loader);
            Class<?> api = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineImages", true, loader);
            Class<?> imageClass = Class.forName("net.momirealms.craftengine.core.font.Image", true, loader);
            Object key = keyClass.getMethod("from", String.class).invoke(null, id);
            Object image = api.getMethod("byId", keyClass).invoke(null, key);
            if (image == null)
            {
                throw new IllegalArgumentException("CraftEngine 图片未注册: " + id);
            }
            // ReferenceImage resolves aliases such as uno:red_0 -> uno:deck:0:0.
            int codepoint = (int) imageClass.getMethod("codepointAt", int.class, int.class)
                    .invoke(image, row, column);
            String miniMessage = (String) imageClass.getMethod("miniMessageAt", int.class, int.class)
                    .invoke(image, row, column);
            Matcher font = FONT.matcher(miniMessage);
            if (codepoint == 0 || !Character.isValidCodePoint(codepoint) || !font.find())
            {
                throw new IllegalArgumentException("CraftEngine 图片没有有效字符或字体: " + id);
            }
            return Component.text(new String(Character.toChars(codepoint))).font(Key.key(font.group(1)));
        }
        catch (ReflectiveOperationException | LinkageError exception)
        {
            Throwable cause = exception instanceof InvocationTargetException invocation
                    ? invocation.getTargetException() : exception;
            throw new IllegalArgumentException("CraftEngine 图片解析失败 " + id + ": "
                    + cause.getClass().getSimpleName() + " " + cause.getMessage(), cause);
        }
    }
}
