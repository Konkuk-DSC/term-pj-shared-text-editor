package com.editor.common.payload;

public class TextDelete {
    private int offset;
    private int length;

    public TextDelete() {}

    public TextDelete(int offset, int length) {
        this.offset = offset;
        this.length = length;
    }

    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }

    public int getLength() { return length; }
    public void setLength(int length) { this.length = length; }
}
