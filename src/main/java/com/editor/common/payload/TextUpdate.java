package com.editor.common.payload;

public class TextUpdate {
    private int offset;
    private int length;
    private String newText;

    public TextUpdate() {}

    public TextUpdate(int offset, int length, String newText) {
        this.offset = offset;
        this.length = length;
        this.newText = newText;
    }

    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }

    public int getLength() { return length; }
    public void setLength(int length) { this.length = length; }

    public String getNewText() { return newText; }
    public void setNewText(String newText) { this.newText = newText; }
}
