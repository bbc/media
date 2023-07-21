package androidx.media3.extractor.text;

import android.text.style.TypefaceSpan;

public interface TtmlFontSpanFactory {

  TypefaceSpan createSpan(String fontFamily);

  TtmlFontSpanFactory DEFAULT = TypefaceSpan::new;
}
