package com.fentai.libsarcui.model;

import java.util.List;

public record UiLoadResult(boolean successful, List<ElementSpec> elements, List<String> warnings)
{
}
