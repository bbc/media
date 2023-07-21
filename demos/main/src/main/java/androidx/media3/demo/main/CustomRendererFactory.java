package androidx.media3.demo.main;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Looper;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.text.SubtitleDecoderFactory;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.text.TextRenderer;
import androidx.media3.extractor.text.SubtitleDecoder;
import androidx.media3.extractor.text.SubtitleStylesProvider;
import androidx.media3.extractor.text.ttml.TtmlDecoder;
import java.util.ArrayList;

// should be like the default one, plus a custom text renderer using the SubtitleDecoderFactory that can build the TtmlDecoder with the user style provider
public class CustomRendererFactory extends DefaultRenderersFactory {
  private final SubtitleStylesProvider subtitleStylesProvider;

  /**
   * @param context A {@link Context}.
   */
  public CustomRendererFactory(Context context) {
    super(context);

    ArrayList<Typeface> customTypefaces = new ArrayList<>();
    customTypefaces.add(Typeface.createFromAsset(context.getAssets(), "Wingdings.ttf"));
    subtitleStylesProvider = new SubtitleStylesProvider(customTypefaces);
  }

  @Override
  protected void buildTextRenderers(Context context, TextOutput output, Looper outputLooper,
      @ExtensionRendererMode int extensionRendererMode, ArrayList<Renderer> out) {
    TextRenderer customTypefaceTtmlRenderer = new TextRenderer(output, outputLooper,
        new SubtitleDecoderFactory() {
          @Override
          public boolean supportsFormat(Format format) {
            return MimeTypes.APPLICATION_TTML.equals(format.sampleMimeType);
          }

          @Override
          public SubtitleDecoder createDecoder(Format format) {
            return new TtmlDecoder(subtitleStylesProvider);
          }
        });

    out.add(customTypefaceTtmlRenderer);
  }
}
