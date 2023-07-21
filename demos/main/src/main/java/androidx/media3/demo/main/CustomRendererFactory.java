package androidx.media3.demo.main;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Looper;
import android.text.style.TypefaceSpan;
import androidx.annotation.RequiresApi;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.text.SubtitleDecoderFactory;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.text.TextRenderer;
import androidx.media3.extractor.text.SubtitleDecoder;
import androidx.media3.extractor.text.ttml.TtmlDecoder;
import java.util.ArrayList;

// should be like the default one, plus a custom text renderer using the SubtitleDecoderFactory that can build the TtmlDecoder with the user style provider
@RequiresApi(api = Build.VERSION_CODES.P)
public class CustomRendererFactory extends DefaultRenderersFactory {

  private SubtitleDecoderFactory subtitleDecoderFactory;

  /**
   * @param context A {@link Context}.
   */
  public CustomRendererFactory(Context context) {
    super(context);

    this.subtitleDecoderFactory = new CustomTypefaceTtmlDecoderFactory(context);
  }

  @Override
  protected void buildTextRenderers(Context context, TextOutput output, Looper outputLooper,
      @ExtensionRendererMode int extensionRendererMode, ArrayList<Renderer> out) {
    TextRenderer customTypefaceTtmlRenderer =
        new TextRenderer(output, outputLooper, subtitleDecoderFactory);

    out.add(customTypefaceTtmlRenderer);
  }

  class CustomTypefaceTtmlDecoderFactory implements SubtitleDecoderFactory {

    private final Typeface typeface;

    CustomTypefaceTtmlDecoderFactory(Context context) {
      this.typeface = Typeface.createFromAsset(context.getAssets(), "Wingdings.ttf");
    }

    @Override
    public boolean supportsFormat(Format format) {
      return MimeTypes.APPLICATION_TTML.equals(format.sampleMimeType);
    }

    @Override
    public SubtitleDecoder createDecoder(Format format) {
      return new TtmlDecoder(fontFamily -> new TypefaceSpan(typeface));
    }
  }
}
