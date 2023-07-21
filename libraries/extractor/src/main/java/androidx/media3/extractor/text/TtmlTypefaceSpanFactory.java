package androidx.media3.extractor.text;

import android.text.style.TypefaceSpan;

public interface TtmlTypefaceSpanFactory {
  TypefaceSpan createSpan(String fontFamily);
}
