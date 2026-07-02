package network.skypvp.shared;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class ServerTextUtilLayoutTest {
    @Test
    void layoutThreeZonePinsRightSegmentToLineEnd() {
        Component left = ServerTextUtil.miniMessageComponent("<aqua>52:15");
        Component center = ServerTextUtil.miniMessageComponent("<white>357 Magnum <dark_gray>| <aqua>2/6");
        Component right = ServerTextUtil.miniMessageComponent("<gold>2 <gray>alive • active");
        Component line = ServerTextUtil.layoutThreeZone(left, center, right, 320);
        int leftIndex = line.toString().indexOf("52:15");
        int centerIndex = line.toString().indexOf("357 Magnum");
        int rightIndex = line.toString().indexOf("alive");
        assertTrue(leftIndex >= 0);
        assertTrue(centerIndex > leftIndex);
        assertTrue(rightIndex > centerIndex);
        int measuredWidth = ServerTextUtil.componentVisibleWidth(line);
        assertTrue(measuredWidth <= 320, "line should not extend past action bar width");
    }
}
