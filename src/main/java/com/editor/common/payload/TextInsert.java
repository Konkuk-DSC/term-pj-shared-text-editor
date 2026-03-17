package com.editor.common.payload;

public class TextInsert {
    private int offset;
    private String text;

    public TextInsert() {}

    public TextInsert(int offset, String text) {
        this.offset = offset;
        this.text = text;
    }

    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
}
