package games.strategy.engine.data.properties;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

final class GamePropertiesTest {
  @Nested
  final class ResolvedGetCacheTest {
    @Test
    void getReturnsSetValueAndSeesUpdateAfterSet() {
      final GameProperties properties = new GameProperties(new GameData());
      properties.set("IgnoreTransportInMovement", Boolean.TRUE);
      assertThat(properties.get("IgnoreTransportInMovement", false)).isTrue();
      // cache hit
      assertThat(properties.get("IgnoreTransportInMovement", false)).isTrue();
      properties.set("IgnoreTransportInMovement", Boolean.FALSE);
      assertThat(properties.get("IgnoreTransportInMovement", true)).isFalse();
    }

    @Test
    void missingKeyUsesDefaultAndCanBeFilledLater() {
      final GameProperties properties = new GameProperties(new GameData());
      assertThat(properties.get("missing", true)).isTrue();
      properties.set("missing", Boolean.FALSE);
      assertThat(properties.get("missing", true)).isFalse();
    }
  }

  @Nested
  final class ReadWriteEditablePropertiesTest {
    @Test
    void shouldBeAbleToRoundTripEditableProperties() throws Exception {
      final List<StringProperty> expected =
          List.of(
              new StringProperty("name1", "description1", "value1"),
              new StringProperty("name2", "description2", "value2"),
              new StringProperty("name3", "description3", "value3"));

      final byte[] bytes = GameProperties.writeEditableProperties(expected);
      final List<IEditableProperty<?>> actual = GameProperties.readEditableProperties(bytes);

      assertThat(actual).isEqualTo(expected);
    }
  }
}
