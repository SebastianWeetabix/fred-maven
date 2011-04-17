package freenet.clients.http;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import freenet.client.filter.PushingTagReplacerCallback;
import freenet.l10n.NodeL10n;
import freenet.node.DarknetPeerNode;
import freenet.node.Node;
import freenet.node.SecurityLevels;
import freenet.pluginmanager.FredPluginL10n;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;
import freenet.support.io.FileUtil;

/** Simple class to output standard heads and tail for web interface pages. 
*/
public final class PageMaker {
	
	public enum THEME {
		BOXED("boxed", "Boxed", "", false, false),
		CLEAN("clean", "Clean", "Mr. Proper", false, false),
		CLEAN_DROPDOWN("clean-dropdown", "Clean (Dropdown menu)", "Clean theme with a dropdown menu.", false, false),
		CLEAN_STATIC("clean-static", "Clean (Static menu)", "Clean theme with a static menu.", false, false),
		GRAYANDBLUE("grayandblue", "Gray And Blue", "", false, false),
		SKY("sky", "Sky", "", false, false),
		MINIMALBLUE("minimalblue", "Minimal Blue", "A minimalistic theme in blue", false, false),
		MINIMALISTIC("minimalist", "Minimalistic", "A very minimalistic theme based on Google's designs", true, true),
		RABBIT_HOLE("rabbit-hole", "Into the Rabbit Hole", "Simple and clean theme", false, false);

		
		public static final String[] possibleValues = {
			BOXED.code,
			CLEAN.code,
			CLEAN_DROPDOWN.code,
			CLEAN_STATIC.code,
			GRAYANDBLUE.code,
			SKY.code,
			MINIMALBLUE.code,
			MINIMALISTIC.code,
			RABBIT_HOLE.code
		};
		
		public final String code;  // the internal name
		public final String name;  // the name in "human form"
		public final String description; // description
		/**
		 * If true, the activelinks will appear on the welcome page, whether
		 * the user has enabled them or not.
		 */
		public final boolean forceActivelinks;
		/**
		 * If true, the "Fetch a key" infobox will appear above the bookmarks
		 * infobox on the welcome page.
		 */
		public final boolean fetchKeyBoxAboveBookmarks;
		
		private THEME(String code, String name, String description) {
			this(code, name, description, false, false);
		}

		private THEME(String code, String name, String description, boolean forceActivelinks, boolean fetchKeyBoxAboveBookmarks) {
			this.code = code;
			this.name = name;
			this.description = description;
			this.forceActivelinks = forceActivelinks;
			this.fetchKeyBoxAboveBookmarks = fetchKeyBoxAboveBookmarks;
		}

		public static THEME themeFromName(String cssName) {
			for(THEME t : THEME.values()) {
				if(t.code.equalsIgnoreCase(cssName) ||
				   t.name.equalsIgnoreCase(cssName))
				{
					return t;
				}
			}
			return getDefault();
		}

		public static THEME getDefault() {
			return THEME.CLEAN;
		}
	}	
	
	public static final int MODE_SIMPLE = 1;
	public static final int MODE_ADVANCED = 2;
	private THEME theme;
	private File override;
	private final Node node;
	
	private List<SubMenu> menuList = new ArrayList<SubMenu>();
	private Map<String, SubMenu> subMenus = new HashMap<String, SubMenu>();
	
	private static class SubMenu {
		
		/** Name of the submenu */
		private final String navigationLinkText;
		/** Link if the user clicks on the submenu itself */
		private final String defaultNavigationLink;
		/** Tooltip */
		private final String defaultNavigationLinkTitle;
		
		private final FredPluginL10n plugin;
		
		private final List<String> navigationLinkTexts = new ArrayList<String>();
		private final List<String> navigationLinkTextsNonFull = new ArrayList<String>();
		private final Map<String, String> navigationLinkTitles = new HashMap<String, String>();
		private final Map<String, String> navigationLinks = new HashMap<String, String>();
		private final Map<String, LinkEnabledCallback>  navigationLinkCallbacks = new HashMap<String, LinkEnabledCallback>();
		private final Map<String, FredPluginL10n> navigationLinkL10n = new HashMap<String, FredPluginL10n>();
		
		public SubMenu(String link, String name, String title, FredPluginL10n plugin) {
			this.navigationLinkText = name;
			this.defaultNavigationLink = link;
			this.defaultNavigationLinkTitle = title;
			this.plugin = plugin;
		}

		public void addNavigationLink(String path, String name, String title, boolean fullOnly, LinkEnabledCallback cb, FredPluginL10n l10n) {
			navigationLinkTexts.add(name);
			if(!fullOnly)
				navigationLinkTextsNonFull.add(name);
			navigationLinkTitles.put(name, title);
			navigationLinks.put(name, path);
			if(cb != null)
				navigationLinkCallbacks.put(name, cb);
			if (l10n != null)
				navigationLinkL10n.put(name, l10n);
		}

		@Deprecated
		public void removeNavigationLink(String name) {
			navigationLinkTexts.remove(name);
			navigationLinkTextsNonFull.remove(name);
			navigationLinkTitles.remove(name);
			navigationLinks.remove(name);
			navigationLinkL10n.remove(name); //Should this be here? If so, why not remove from navigationLinkCallbacks too
		}

		@Deprecated
		public void removeAllNavigationLinks() {
			navigationLinkTexts.clear();
			navigationLinkTextsNonFull.clear();
			navigationLinkTitles.clear();
			navigationLinks.clear();
			navigationLinkL10n.clear(); //Should this be here? If so, why not clear navigationLinkCallbacks too
		}
	}
	
	protected PageMaker(THEME t, Node n) {
		setTheme(t);
		this.node = n;
	}
	
	void setOverride(File f) {
		this.override = f;
	}
	
	public void setTheme(THEME theme2) {
		if (theme2 == null) {
			this.theme = THEME.getDefault();
		} else {
			URL themeurl = getClass().getResource("staticfiles/themes/" + theme2.code + "/theme.css");
			if (themeurl == null)
				this.theme = THEME.getDefault();
			else
				this.theme = theme2;
		}
	}

	public synchronized void addNavigationCategory(String link, String name, String title, FredPluginL10n plugin) {
		SubMenu menu = new SubMenu(link, name, title, plugin);
		subMenus.put(name, menu);
		menuList.add(menu);
	}
	
	/**
	 * Add a navigation category to the menu at a given offset.
	 * @param menuOffset The position of the link in FProxy's menu. 0 = left.
	 */
	public synchronized void addNavigationCategory(String link, String name, String title, FredPluginL10n plugin, int menuOffset) {
		SubMenu menu = new SubMenu(link, name, title, plugin);
		subMenus.put(name, menu);
		menuList.add(menuOffset, menu);
	}
	

	public synchronized void removeNavigationCategory(String name) {
		SubMenu menu = subMenus.remove(name);
		if (menu == null) {
			Logger.error(this, "can't remove navigation category, name="+name);
			return;
		}	
		menuList.remove(menu);
	}
	
	public synchronized void addNavigationLink(String menutext, String path, String name, String title, boolean fullOnly, LinkEnabledCallback cb, FredPluginL10n l10n) {
		SubMenu menu = subMenus.get(menutext);
		if(menu == null)
			throw new NullPointerException("there is no menu named "+menutext);
		menu.addNavigationLink(path, name, title, fullOnly, cb, l10n);
	}
	
	/* FIXME: Implement a proper way for chosing what the menu looks like upon handleHTTPGet/Post */
	@Deprecated
	public synchronized void removeNavigationLink(String menutext, String name) {
		SubMenu menu = subMenus.get(menutext);
		menu.removeNavigationLink(name);
	}
	
	@Deprecated
	public synchronized void removeAllNavigationLinks() {
		for(SubMenu menu : subMenus.values())
			menu.removeAllNavigationLinks();
	}
	
	public HTMLNode createBackLink(ToadletContext toadletContext, String name) {
		String referer = toadletContext.getHeaders().get("referer");
		if (referer != null) {
			return new HTMLNode("a", new String[] { "href", "title" }, new String[] { referer, name }, name);
		}
		return new HTMLNode("a", new String[] { "href", "title" }, new String[] { "javascript:back()", name }, name);
	}
	
	public PageNode getPageNode(String title, ToadletContext ctx) {
		return getPageNode(title, true, ctx);
	}

	public PageNode getPageNode(String title, boolean renderNavigationLinks, ToadletContext ctx) {
		boolean fullAccess = ctx == null ? false : ctx.isAllowedFullAccess();
		HTMLNode pageNode = new HTMLNode.HTMLDoctype("html", "-//W3C//DTD XHTML 1.1//EN");
		HTMLNode htmlNode = pageNode.addChild("html", "xml:lang", NodeL10n.getBase().getSelectedLanguage().isoCode);
		HTMLNode headNode = htmlNode.addChild("head");
		headNode.addChild("meta", new String[] { "http-equiv", "content" }, new String[] { "Content-Type", "text/html; charset=utf-8" });
		headNode.addChild("title", title + " - Freenet");
		//To make something only rendered when javascript is on, then add the jsonly class to it
		headNode.addChild("noscript").addChild("style"," .jsonly {display:none;}");
		if(override == null)
			headNode.addChild("link", new String[] { "rel", "href", "type", "title" }, new String[] { "stylesheet", "/static/themes/" + theme.code + "/theme.css", "text/css", theme.code });
		else
			headNode.addChild(getOverrideContent());
		for (THEME t: THEME.values()) {
			String themeName = t.code;
			headNode.addChild("link", new String[] { "rel", "href", "type", "media", "title" }, new String[] { "alternate stylesheet", "/static/themes/" + themeName + "/theme.css", "text/css", "screen", themeName });
		}
		
		boolean webPushingEnabled = 
			ctx != null && ctx.getContainer().isFProxyJavascriptEnabled() && ctx.getContainer().isFProxyWebPushingEnabled();
		
		// Add the generated javascript, if it and pushing is enabled
		if (webPushingEnabled) headNode.addChild("script", new String[] { "type", "language", "src" }, new String[] {
				"text/javascript", "javascript", "/static/freenetjs/freenetjs.nocache.js" });
		
		Toadlet t;
		if (ctx != null) {
			t = ctx.activeToadlet();
			t = t.showAsToadlet();
		} else
			t = null;
		String activePath = "";
		if(t != null) activePath = t.path();
		HTMLNode bodyNode = htmlNode.addChild("body");
		//Add a hidden input that has the request's id
		if(webPushingEnabled)
			bodyNode.addChild("input",new String[]{"type","name","value","id"},new String[]{"hidden","requestId",ctx.getUniqueId(),"requestId"});
		
		// Add the client-side localization only when pushing is enabled
		if (webPushingEnabled) {
			bodyNode.addChild("script", new String[] { "type", "language" }, new String[] { "text/javascript", "javascript" }).addChild("%", PushingTagReplacerCallback.getClientSideLocalizationScript());
		}
		
		HTMLNode pageDiv = bodyNode.addChild("div", "id", "page");
		HTMLNode topBarDiv = pageDiv.addChild("div", "id", "topbar");

		final HTMLNode statusBarDiv = pageDiv.addChild("div", "id", "statusbar-container").addChild("div", "id", "statusbar");

		 if (node != null && node.clientCore != null) {
			 final HTMLNode alerts = node.clientCore.alerts.createSummary(true);
			 if (alerts != null) {
				 statusBarDiv.addChild(alerts).addAttribute("id", "statusbar-alerts");
				 statusBarDiv.addChild("div", "class", "separator", "\u00a0");
			 }
		 }
	

		statusBarDiv.addChild("div", "id", "statusbar-language").addChild("a", "href", "/config/node#l10n", NodeL10n.getBase().getSelectedLanguage().fullName);

		if (node.clientCore != null && ctx != null) {
			statusBarDiv.addChild("div", "class", "separator", "\u00a0");
			final HTMLNode switchMode = statusBarDiv.addChild("div", "id", "statusbar-switchmode");
			if (ctx.activeToadlet().container.isAdvancedModeEnabled()) {
				switchMode.addAttribute("class", "simple");
				switchMode.addChild("a", "href", "?mode=1", NodeL10n.getBase().getString("StatusBar.switchToSimpleMode"));
			} else {
				switchMode.addAttribute("class", "advanced");
				switchMode.addChild("a", "href", "?mode=2", NodeL10n.getBase().getString("StatusBar.switchToAdvancedMode"));
			}
		}

		if (node != null && node.clientCore != null) {
			statusBarDiv.addChild("div", "class", "separator", "\u00a0");
			final HTMLNode secLevels = statusBarDiv.addChild("div", "id", "statusbar-seclevels", NodeL10n.getBase().getString("SecurityLevels.statusBarPrefix"));

			final HTMLNode network = secLevels.addChild("a", "href", "/seclevels/", SecurityLevels.localisedName(node.securityLevels.getNetworkThreatLevel()) + "\u00a0");
			network.addAttribute("title", NodeL10n.getBase().getString("SecurityLevels.networkThreatLevelShort"));
			network.addAttribute("class", node.securityLevels.getNetworkThreatLevel().toString().toLowerCase());

			final HTMLNode physical = secLevels.addChild("a", "href", "/seclevels/", SecurityLevels.localisedName(node.securityLevels.getPhysicalThreatLevel()));
			physical.addAttribute("title", NodeL10n.getBase().getString("SecurityLevels.physicalThreatLevelShort"));
			physical.addAttribute("class", node.securityLevels.getPhysicalThreatLevel().toString().toLowerCase());

			statusBarDiv.addChild("div", "class", "separator", "\u00a0");

			final int connectedPeers = node.peers.countConnectedPeers();
			int darknetTotal = 0;
			for(DarknetPeerNode n : node.peers.getDarknetPeers()) {
				if(n == null) continue;
				if(n.isDisabled()) continue;
				darknetTotal++;
			}
			final int connectedDarknetPeers = node.peers.countConnectedDarknetPeers();
			final int totalPeers = (node.getOpennet() == null) ? (darknetTotal > 0 ? darknetTotal : Integer.MAX_VALUE) : node.getOpennet().getNumberOfConnectedPeersToAimIncludingDarknet();
			final double connectedRatio = ((double)connectedPeers) / (double)totalPeers;
			final String additionnalClass;

			// If we use Opennet, we color the bar by the ratio of connected nodes
			if(connectedPeers > connectedDarknetPeers) {
				if (connectedRatio < 0.3D || connectedPeers < 3) {
					additionnalClass = "very-few-peers";
				} else if (connectedRatio < 0.5D) {
					additionnalClass = "few-peers";
				} else if (connectedRatio < 0.75D) {
					additionnalClass = "avg-peers";
				} else {
					additionnalClass = "full-peers";
				}
			} else {
				// If we are darknet only, we color by absolute connected peers
				if (connectedDarknetPeers < 3) {
					additionnalClass = "very-few-peers";
				} else if (connectedDarknetPeers < 5) {
					additionnalClass = "few-peers";
				} else if (connectedDarknetPeers < 10) {
					additionnalClass = "avg-peers";
				} else {
					additionnalClass = "full-peers";
				}
			}

			HTMLNode progressBar = statusBarDiv.addChild("div", "class", "progressbar");
			progressBar.addChild("div", new String[] { "class", "style" }, new String[] { "progressbar-done progressbar-peers " + additionnalClass, "width: " +
					Math.floor(100*connectedRatio) + "%;" });

			progressBar.addChild("div", new String[] { "class", "title" }, new String[] { "progress_fraction_finalized", NodeL10n.getBase().getString("StatusBar.connectedPeers", new String[]{"X", "Y"},
					new String[]{Integer.toString(node.peers.countConnectedDarknetPeers()), Integer.toString(node.peers.countConnectedOpennetPeers())}) },
					Integer.toString(connectedPeers) + ((totalPeers != Integer.MAX_VALUE) ? " / " + Integer.toString(totalPeers) : ""));
		}

		topBarDiv.addChild("h1", title);
		if (renderNavigationLinks) {
			SubMenu selected = null;
			// Render the full menu.
			HTMLNode navbarDiv = pageDiv.addChild("div", "id", "navbar");
			HTMLNode navbarUl = navbarDiv.addChild("ul", "id", "navlist");
			synchronized (this) {
				for (SubMenu menu : menuList) {
					HTMLNode subnavlist = new HTMLNode("ul");
					boolean isSelected = false;
					boolean nonEmpty = false;
					for (String navigationLink :  fullAccess ? menu.navigationLinkTexts : menu.navigationLinkTextsNonFull) {
						LinkEnabledCallback cb = menu.navigationLinkCallbacks.get(navigationLink);
						if(cb != null && !cb.isEnabled(ctx)) continue;
						nonEmpty = true;
						String navigationTitle = menu.navigationLinkTitles.get(navigationLink);
						String navigationPath = menu.navigationLinks.get(navigationLink);
						HTMLNode sublistItem;
						if(activePath.equals(navigationPath)) {
							sublistItem = subnavlist.addChild("li", "class", "submenuitem-selected");
							isSelected = true;
						} else {
							sublistItem = subnavlist.addChild("li", "class", "submenuitem-not-selected");;
						}
						
						FredPluginL10n l10n = menu.navigationLinkL10n.get(navigationLink);
						if(l10n == null) l10n = menu.plugin;
						if(l10n != null) {
							if(navigationTitle != null) {
								String newNavigationTitle = l10n.getString(navigationTitle);
								if(newNavigationTitle == null) {
									Logger.error(this, "Plugin '"+l10n+"' did return null in getString(key)!");
								} else {
									navigationTitle = newNavigationTitle;
								}
							}
							if(navigationLink != null) {
								String newNavigationLink = l10n.getString(navigationLink);
								if(newNavigationLink == null) {
									Logger.error(this, "Plugin '"+l10n+"' did return null in getString(key)!");
								} else {
									navigationLink = newNavigationLink;
								}
							}
						} else {
							if(navigationTitle != null) navigationTitle = NodeL10n.getBase().getString(navigationTitle);
							if(navigationLink != null) navigationLink = NodeL10n.getBase().getString(navigationLink);
						}
						if(navigationTitle != null)
							sublistItem.addChild("a", new String[] { "href", "title" }, new String[] { navigationPath, navigationTitle }, navigationLink);
						else
							sublistItem.addChild("a", "href", navigationPath, navigationLink);
					}
					if(nonEmpty) {
						HTMLNode listItem;
						if(isSelected) {
							selected = menu;
							subnavlist.addAttribute("class", "subnavlist-selected");
							listItem = new HTMLNode("li", "id", "navlist-selected");
						} else {
							subnavlist.addAttribute("class", "subnavlist");
							listItem = new HTMLNode("li", "class", "navlist-not-selected");
						}
						String menuItemTitle = menu.defaultNavigationLinkTitle;
						String text = menu.navigationLinkText;
						if(menu.plugin == null) {
							menuItemTitle = NodeL10n.getBase().getString(menuItemTitle);
							text = NodeL10n.getBase().getString(text);
						} else {
							String newTitle = menu.plugin.getString(menuItemTitle);
							if(newTitle == null) {
								Logger.error(this, "Plugin '"+menu.plugin+"' did return null in getString(key)!");
							} else {
								menuItemTitle = newTitle;
							}
							String newText = menu.plugin.getString(text);
							if(newText == null) {
								Logger.error(this, "Plugin '"+menu.plugin+"' did return null in getString(key)!");
							} else {
								text = newText;
							}
						}
						
						listItem.addChild("a", new String[] { "href", "title" }, new String[] { menu.defaultNavigationLink, menuItemTitle }, text);
						listItem.addChild(subnavlist);
						navbarUl.addChild(listItem);
					}
				}
			}
			// Some themes want the selected submenu separately.
			if(selected != null) {
				HTMLNode div = new HTMLNode("div", "id", "selected-subnavbar");
				HTMLNode subnavlist = div.addChild("ul", "id", "selected-subnavbar-list");
				boolean nonEmpty = false;
				for (String navigationLink :  fullAccess ? selected.navigationLinkTexts : selected.navigationLinkTextsNonFull) {
					LinkEnabledCallback cb = selected.navigationLinkCallbacks.get(navigationLink);
					if(cb != null && !cb.isEnabled(ctx)) continue;
					nonEmpty = true;
					String navigationTitle = selected.navigationLinkTitles.get(navigationLink);
					String navigationPath = selected.navigationLinks.get(navigationLink);
					HTMLNode sublistItem;
					if(activePath.equals(navigationPath)) {
						sublistItem = subnavlist.addChild("li", "class", "submenuitem-selected");
					} else {
						sublistItem = subnavlist.addChild("li");
					}
					
					FredPluginL10n l10n = selected.navigationLinkL10n.get(navigationLink);
					if (l10n == null) l10n = selected.plugin;
					if(l10n != null) {
						if(navigationTitle != null) navigationTitle = l10n.getString(navigationTitle);
						if(navigationLink != null) navigationLink = l10n.getString(navigationLink);
					} else {
						if(navigationTitle != null) navigationTitle = NodeL10n.getBase().getString(navigationTitle);
						if(navigationLink != null) navigationLink = NodeL10n.getBase().getString(navigationLink);
					}
					if(navigationTitle != null)
						sublistItem.addChild("a", new String[] { "href", "title" }, new String[] { navigationPath, navigationTitle }, navigationLink);
					else
						sublistItem.addChild("a", "href", navigationPath, navigationLink);
				}
				if(nonEmpty)
					pageDiv.addChild(div);
			}
		}
		HTMLNode contentDiv = pageDiv.addChild("div", "id", "content");
		return new PageNode(pageNode, headNode, contentDiv);
	}

	public THEME getTheme() {
		return this.theme;
	}

	public InfoboxNode getInfobox(String header) {
		return getInfobox(header, null, false);
	}

	public InfoboxNode getInfobox(HTMLNode header) {
		return getInfobox(header, null, false);
	}

	public InfoboxNode getInfobox(String category, String header) {
		return getInfobox(category, header, null, false);
	}

	public HTMLNode getInfobox(String category, String header, HTMLNode parent) {
		return getInfobox(category, header, parent, null, false);
	}

	public InfoboxNode getInfobox(String category, HTMLNode header) {
		return getInfobox(category, header, null, false);
	}

	public InfoboxNode getInfobox(String header, String title, boolean isUnique) {
		if (header == null) throw new NullPointerException();
		return getInfobox(new HTMLNode("#", header), title, isUnique);
	}
	
	public InfoboxNode getInfobox(HTMLNode header, String title, boolean isUnique) {
		if (header == null) throw new NullPointerException();
		return getInfobox(null, header, title, isUnique);
	}

	public InfoboxNode getInfobox(String category, String header, String title, boolean isUnique) {
		if (header == null) throw new NullPointerException();
		return getInfobox(category, new HTMLNode("#", header), title, isUnique);
	}

	/** Create an infobox, attach it to the given parent, and return the content node. */
	public HTMLNode getInfobox(String category, String header, HTMLNode parent, String title, boolean isUnique) {
		InfoboxNode node = getInfobox(category, header, title, isUnique);
		parent.addChild(node.outer);
		return node.content;
	}

	/**
	 * Returns an infobox with the given style and header.
	 * 
	 * @param category
	 *            The CSS styles, separated by a space (' ')
	 * @param header
	 *            The header HTML node
	 * @return The infobox
	 */
	public InfoboxNode getInfobox(String category, HTMLNode header, String title, boolean isUnique) {
		if (header == null) throw new NullPointerException();

		StringBuffer classes = new StringBuffer("infobox");
		if(category != null) {
			classes.append(" ");
			classes.append(category);
		}
		if(title != null && !isUnique) {
			classes.append(" ");
			classes.append(title);
		}

		HTMLNode infobox = new HTMLNode("div", "class", classes.toString());

		if(title != null && isUnique) {
			infobox.addAttribute("id", title);
		}

		infobox.addChild("div", "class", "infobox-header").addChild(header);
		return new InfoboxNode(infobox, infobox.addChild("div", "class", "infobox-content"));
	}
	
	private HTMLNode getOverrideContent() {
		HTMLNode result = new HTMLNode("style", "type", "text/css");
		
		try {
			result.addChild("#", FileUtil.readUTF(override));
		} catch (IOException e) {
			Logger.error(this, "Got an IOE: " + e.getMessage(), e);
		}
		
		return result;
	}
	
	/** Call this before getPageNode(), so the menus reflect the advanced mode setting. */
	public int parseMode(HTTPRequest req, ToadletContainer container) {
		int mode = container.isAdvancedModeEnabled() ? MODE_ADVANCED : MODE_SIMPLE;
		
		if(req.isParameterSet("mode")) {
			mode = req.getIntParam("mode", mode);
			if(mode == MODE_ADVANCED)
				container.setAdvancedMode(true);
			else
				container.setAdvancedMode(false);
		}
		
		return mode;
	}
	
	private static final String l10n(String string) {
		return NodeL10n.getBase().getString("PageMaker." + string);
	}
}
