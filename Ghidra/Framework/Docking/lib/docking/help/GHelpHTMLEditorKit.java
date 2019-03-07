/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package docking.help;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import java.net.*;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JEditorPane;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.HTML.Tag;

import generic.jar.ResourceFile;
import ghidra.framework.Application;
import ghidra.framework.preferences.Preferences;
import ghidra.util.Msg;
import resources.Icons;
import resources.ResourceManager;
import utilities.util.FileUtilities;

/**
 * A class that allows Ghidra to intercept JavaHelp navigation events in order to resolve them
 * to Ghidra's help system.  Without this class, contribution plugins have no way of 
 * referencing help documents within Ghidra's default help location.
 * <p>
 * This class is currently installed by the {@link GHelpSet}.
 * 
 * @see GHelpSet
 */
public class GHelpHTMLEditorKit extends HTMLEditorKit {

	private static final String G_HELP_STYLE_SHEET = "help/shared/Frontpage.css";

	/** A pattern to strip the font size value from a line of CSS */
	private static final Pattern FONT_SIZE_PATTERN = Pattern.compile("font-size:\\s*(\\d{1,2})");
	private static final String HELP_WINDOW_ZOOM_FACTOR = "HELP.WINDOW.FONT.SIZE.MODIFIER";
	private static int fontSizeModifier;

	private HyperlinkListener[] delegateListeners = null;
	private HyperlinkListener resolverHyperlinkListener;

	public GHelpHTMLEditorKit() {
		fontSizeModifier =
			Integer.valueOf(Preferences.getProperty(HELP_WINDOW_ZOOM_FACTOR, "0", true));
	}

	@Override
	public ViewFactory getViewFactory() {
		return new GHelpHTMLFactory();
	}

	@Override
	public void install(JEditorPane c) {
		super.install(c);

		delegateListeners = c.getHyperlinkListeners();
		for (HyperlinkListener listener : delegateListeners) {
			c.removeHyperlinkListener(listener);
		}

		resolverHyperlinkListener = new ResolverHyperlinkListener();
		c.addHyperlinkListener(resolverHyperlinkListener);

		// add a listener to report trace information
		c.addPropertyChangeListener(new PropertyChangeListener() {
			@Override
			public void propertyChange(PropertyChangeEvent evt) {
				String propertyName = evt.getPropertyName();
				if ("page".equals(propertyName)) {
					Msg.trace(this, "Page loaded: " + evt.getNewValue());
				}
			}
		});
	}

	@Override
	public void deinstall(JEditorPane c) {

		c.removeHyperlinkListener(resolverHyperlinkListener);

		for (HyperlinkListener listener : delegateListeners) {
			c.addHyperlinkListener(listener);
		}

		super.deinstall(c);
	}

	private class ResolverHyperlinkListener implements HyperlinkListener {
		@Override
		public void hyperlinkUpdate(HyperlinkEvent e) {

			if (delegateListeners == null) {
				return;
			}

			if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
				Msg.trace(this, "Link activated: " + e.getURL());
				e = validateURL(e);
				Msg.trace(this, "Validated event: " + e.getURL());
			}

			for (HyperlinkListener listener : delegateListeners) {
				listener.hyperlinkUpdate(e);
			}
		}
	}

	/** 
	 * Tests the URL of the given event.  If the URL is invalid, a new event may be created if
	 *  a new, valid URL can be created. Creates a new event with a patched URL if 
	 *  the given event's URL is invalid.
	 */
	private HyperlinkEvent validateURL(HyperlinkEvent event) {
		URL url = event.getURL();
		try {
			url.openStream();// assume that this will fail if the file does not exist
		}
		catch (IOException ioe) {
			// assume this means that the url is invalid
			Msg.trace(this, "URL of link is invalid: " + url.toExternalForm());
			return maybeCreateNewHyperlinkEventWithUpdatedURL(event);
		}

		return event;// url is fine
	}

	/** Generates a new event with a URL based upon Ghidra's resources if needed. */
	private HyperlinkEvent maybeCreateNewHyperlinkEventWithUpdatedURL(HyperlinkEvent event) {
		Element element = event.getSourceElement();
		if (element == null) {
			return event;// this shouldn't happen since we were triggered from an A tag
		}

		AttributeSet a = element.getAttributes();
		AttributeSet anchor = (AttributeSet) a.getAttribute(HTML.Tag.A);
		if (anchor == null) {
			return event;// this shouldn't happen since we were triggered from an A tag
		}

		String HREF = (String) anchor.getAttribute(HTML.Attribute.HREF);
		Msg.trace(this, "HREF of <a> tag: " + HREF);
		URL newUrl = getURLForHREFFromResources(HREF);
		if (newUrl == null) {
			return event;// unable to locate a resource by the name--bad link!
		}

		return new HyperlinkEvent(event.getSource(), event.getEventType(), newUrl,
			event.getDescription(), event.getSourceElement());
	}

	private URL getURLForHREFFromResources(String originalHREF) {
		int anchorIndex = originalHREF.indexOf("#");
		String HREF = originalHREF;
		String anchor = null;
		if (anchorIndex != -1) {
			HREF = HREF.substring(0, anchorIndex);
			anchor = originalHREF.substring(anchorIndex);
		}

		// look for a URL using an installation environment setup...
		URL newUrl = ResourceManager.getResource(HREF);
		if (newUrl != null) {
			return createURLWithAnchor(newUrl, anchor);
		}

		//
		// The item was not found by the ResourceManager (i.e., it is not in a 'resources' 
		// directory).  See if it may be a relative link to a build's installation root (like
		// a file in <install dir>/docs).
		// 
		newUrl = findApplicationfile(HREF);
		return newUrl;
	}

	private URL createURLWithAnchor(URL anchorlessURL, String anchor) {
		if (anchorlessURL == null) {
			return anchorlessURL;
		}

		if (anchor == null) {
			// nothing to do
			return anchorlessURL;
		}

		try {
			// put the anchor back into the URL                
			return new URL(anchorlessURL, anchor);
		}
		catch (MalformedURLException e) {
			// shouldn't happen, since the file exists
			Msg.showError(this, null, "Unexpected Error",
				"Unexpected error creating a valid URL: " + anchorlessURL + "#" + anchor);
			return null;
		}
	}

	@Override
	public void read(Reader in, Document doc, int pos) throws IOException, BadLocationException {

		super.read(in, doc, pos);

		HTMLDocument htmlDoc = (HTMLDocument) doc;
		loadGHelpStyleSheet(htmlDoc);
	}

	private void loadGHelpStyleSheet(HTMLDocument doc) {

		Reader reader = getGStyleSheetReader();
		if (reader == null) {
			return;
		}

		StyleSheet ss = doc.getStyleSheet();
		try {
			ss.loadRules(reader, null);
		}
		catch (IOException e) {
			// shouldn't happen
			Msg.debug(this, "Unable to load help style sheet");
		}
	}

	private Reader getGStyleSheetReader() {
		URL url = getGStyleSheetURL();
		if (url == null) {
			return null;
		}

		StringBuffer buffy = new StringBuffer();
		try {
			List<String> lines = FileUtilities.getLines(url);
			for (String line : lines) {
				changePixels(line, fontSizeModifier, buffy);
				buffy.append('\n');
			}
		}
		catch (IOException e) {
			// shouldn't happen
			Msg.debug(this, "Unable to read the lines of the help style sheet: " + url);
		}

		StringReader reader = new StringReader(buffy.toString());
		return reader;
	}

	private void changePixels(String line, int amount, StringBuffer buffy) {

		Matcher matcher = FONT_SIZE_PATTERN.matcher(line);
		while (matcher.find()) {
			String oldFontSize = matcher.group(1);
			String adjustFontSize = adjustFontSize(oldFontSize);
			matcher.appendReplacement(buffy, "font-size: " + adjustFontSize);
		}

		matcher.appendTail(buffy);
	}

	private String adjustFontSize(String sizeString) {
		try {
			int size = Integer.parseInt(sizeString);
			String adjusted = Integer.toString(size + fontSizeModifier);
			return adjusted;
		}
		catch (NumberFormatException e) {
			Msg.debug(this, "Unable to parse font size string '" + sizeString + "'");
		}
		return sizeString;
	}

	private URL getGStyleSheetURL() {
		URL GStyleSheetURL = ResourceManager.getResource(G_HELP_STYLE_SHEET);
		if (GStyleSheetURL != null) {
			return GStyleSheetURL;
		}

		return findModuleFile("help/shared/FrontPage.css");
	}

	private URL findApplicationfile(String relativePath) {
		ResourceFile installDir = Application.getInstallationDirectory();
		ResourceFile file = new ResourceFile(installDir, relativePath);
		if (file.exists()) {
			try {
				return file.toURL();
			}
			catch (MalformedURLException e) {
				Msg.showError(this, null, "Unexpected Error",
					"Unexpected error parsing file to URL: " + file);
			}
		}
		return null;
	}

	private URL findModuleFile(String relativePath) {
		Collection<ResourceFile> moduleDirs = Application.getModuleRootDirectories();
		for (ResourceFile dir : moduleDirs) {
			ResourceFile file = new ResourceFile(dir, relativePath);
			if (file.exists()) {
				try {
					return file.toURL();
				}
				catch (MalformedURLException e) {
					Msg.showError(this, null, "Unexpected Error",
						"Unexpected error parsing file to URL: " + file);
					return null;
				}
			}
		}
		return null;
	}

	public static void zoomOut() {
		fontSizeModifier -= 2;
		saveZoomFactor();
	}

	public static void zoomIn() {
		fontSizeModifier += 2;
		saveZoomFactor();
	}

	private static void saveZoomFactor() {
		Preferences.setProperty(HELP_WINDOW_ZOOM_FACTOR, Integer.toString(fontSizeModifier));
		Preferences.store();
	}

//==================================================================================================
// Inner Classes
//==================================================================================================	

	private class GHelpHTMLFactory extends HTMLFactory {
		@Override
		public View create(Element e) {

			AttributeSet attributes = e.getAttributes();
			Object elementName = attributes.getAttribute(AbstractDocument.ElementNameAttribute);
			if (elementName != null) {
				// not an HTML element
				return super.create(e);
			}

			Object html = attributes.getAttribute(StyleConstants.NameAttribute);
			if (html instanceof HTML.Tag) {
				HTML.Tag tag = (Tag) html;
				if (tag == HTML.Tag.IMG) {
					return new GHelpImageView(e);
				}
			}

			return super.create(e);
		}

	}

	private class GHelpImageView extends ImageView {

		public GHelpImageView(Element elem) {
			super(elem);
		}

		@Override
		public URL getImageURL() {
			AttributeSet attributes = getElement().getAttributes();
			Object src = attributes.getAttribute(HTML.Attribute.SRC);
			if (src == null) {
				return null;
			}

			String srcString = src.toString();
			if (isJavaCode(srcString)) {
				return getUrlFromJavaCode(srcString);
			}

			HTMLDocument htmlDocument = (HTMLDocument) getDocument();
			URL context = htmlDocument.getBase();
			try {
				URL url = new URL(context, srcString);
				if (FileUtilities.exists(url.toURI())) {
					// it's a good one, let it through
					return url;
				}
			}
			catch (MalformedURLException | URISyntaxException e) {
				// check below
			}

			// Try the ResourceManager.  This will work for images that start with GHelp 
			// relative link syntax such as 'help/', 'help/topics/' and 'images/'
			URL resource = ResourceManager.getResource(srcString);
			return resource;
		}

		private boolean isJavaCode(String src) {
			// not sure of the best way to handle this--be exact for now
			return Icons.isIconsReference(src);
		}

		private URL getUrlFromJavaCode(String src) {
			return Icons.getUrlForIconsReference(src);
		}

	}

}
