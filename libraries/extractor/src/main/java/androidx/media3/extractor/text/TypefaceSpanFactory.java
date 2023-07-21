package androidx.media3.extractor.text;

import android.annotation.SuppressLint;
import android.graphics.Typeface;
import android.text.style.TypefaceSpan;
import java.util.List;

public class TypefaceSpanFactory {

  private List<Typeface> typefaces;

  public TypefaceSpanFactory(List<Typeface> typefaces) {
    this.typefaces = typefaces;
  }

  @SuppressLint("NewApi")
  public TypefaceSpan createSpan(String fontFamily) {
    // TODO: select typeface based on parameter, fallback to preexisting behaviour

    return new TypefaceSpan(typefaces.get(0));
  }

}
