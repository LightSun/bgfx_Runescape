package dev.dennis.osfx.inject.impl;

import dev.dennis.osfx.api.Font;

import java.util.Map;

public class FontImpl implements Font {
    @Override
    public byte[][] getGlyphs() {
        return new byte[0][];
    }

    @Override
    public int[] getGlyphWidths() {
        return new int[0];
    }

    @Override
    public int[] getGlyphHeights() {
        return new int[0];
    }

    @Override
    public Map<byte[], Integer> getGlyphIdMap() {
        return null;
    }

    @Override
    public short getTextureId() {
        return 0;
    }

    @Override
    public void setTextureId(short id) {

    }

    @Override
    public int getTextureSize() {
        return 0;
    }

    @Override
    public void setTextureSize(int size) {

    }
}
