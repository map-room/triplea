package org.triplea.ai.sidecar;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Key → pre-parsed {@link CanonicalGameData}.
 *
 * <p>The key is opaque: Map Room owns edition→key, this class owns key→XML. That split is what lets
 * one edition map to several scenarios — the world_war_ii_v3 package ships both WW2v3-1941.xml and
 * WW2v3-1942.xml.
 *
 * <p>Every entry is parsed eagerly at startup. Parsing is expensive and the sidecar already paid it
 * before serving its first request; loading lazily would move a multi-second parse inside a live
 * decision that has a latency budget.
 */
public final class CanonicalGameDataRegistry {

  private static final Map<String, String> RESOURCES =
      Map.of(
          "ww2global40_2nd_edition", "ww2global40_2nd_edition.xml",
          "ww2v3_1941", "WW2v3-1941.xml");

  private final Map<String, CanonicalGameData> loaded;

  private CanonicalGameDataRegistry(final Map<String, CanonicalGameData> loaded) {
    this.loaded = loaded;
  }

  public static CanonicalGameDataRegistry loadAll() {
    final Map<String, CanonicalGameData> map = new LinkedHashMap<>();
    RESOURCES.forEach((key, resource) -> map.put(key, CanonicalGameData.load(resource)));
    return new CanonicalGameDataRegistry(Map.copyOf(map));
  }

  public CanonicalGameData forKey(final String key) {
    final CanonicalGameData data = loaded.get(key);
    if (data == null) {
      throw new IllegalArgumentException(
          "Unknown gameDataKey: " + key + " (known keys: " + loaded.keySet() + ")");
    }
    return data;
  }
}
