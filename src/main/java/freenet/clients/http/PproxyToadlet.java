package freenet.clients.http;

import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import freenet.client.HighLevelSimpleClient;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.pluginmanager.AccessDeniedPluginHTTPException;
import freenet.pluginmanager.DownloadPluginHTTPException;
import freenet.pluginmanager.NotFoundPluginHTTPException;
import freenet.pluginmanager.PluginHTTPException;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.pluginmanager.PluginManager;
import freenet.pluginmanager.RedirectPluginHTTPException;
import freenet.pluginmanager.PluginManager.OfficialPluginDescription;
import freenet.pluginmanager.PluginManager.PluginProgress;
import freenet.support.HTMLNode;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;
import freenet.support.api.HTTPRequest;

public class PproxyToadlet extends Toadlet {
	private static final int MAX_PLUGIN_NAME_LENGTH = 1024;
	/** Maximum time to wait for a threaded plugin to exit */
	private static final int MAX_THREADED_UNLOAD_WAIT_TIME = 60*1000;
	private final Node node;
	private final NodeClientCore core;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	public PproxyToadlet(HighLevelSimpleClient client, Node node, NodeClientCore core) {
		super(client);
		this.node = node;
		this.core = core;
	}

	public void handleMethodPOST(URI uri, final HTTPRequest request, ToadletContext ctx)
	throws ToadletContextClosedException, IOException {

		MultiValueTable<String, String> headers = new MultiValueTable<String, String>();

		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(core.formPassword)) {
			headers.put("Location", "/plugins/");
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}

		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, l10n("unauthorizedTitle"), l10n("unauthorized"));
			return;
		}

		String path=request.getPath();

		// remove leading / and plugins/ from path
		if(path.startsWith("/")) path = path.substring(1);
		if(path.startsWith("plugins/")) path = path.substring("plugins/".length());

		if(logMINOR) Logger.minor(this, "Pproxy received POST on "+path);

		final PluginManager pm = node.pluginManager;

		if(path.length()>0)
		{
			try
			{
				String plugin = null;
				// split path into plugin class name and 'daa' path for plugin
				int to = path.indexOf("/");
				if(to == -1)
				{
					plugin = path;
				}
				else
				{
					plugin = path.substring(0, to);
				}

				writeHTMLReply(ctx, 200, "OK", pm.handleHTTPPost(plugin, request));
			}
			catch (RedirectPluginHTTPException e) {
				writeTemporaryRedirect(ctx, e.message, e.newLocation);
			}
			catch (NotFoundPluginHTTPException e) {
				sendErrorPage(ctx, NotFoundPluginHTTPException.code, e.message, e.location);
			}
			catch (AccessDeniedPluginHTTPException e) {
				sendErrorPage(ctx, AccessDeniedPluginHTTPException.code, e.message, e.location);
			}
			catch (DownloadPluginHTTPException e) {
				// FIXME: maybe it ought to be defined like sendErrorPage : in toadlets

				MultiValueTable<String, String> head = new MultiValueTable<String, String>();
				head.put("Content-Disposition", "attachment; filename=\"" + e.filename + '"');
				ctx.sendReplyHeaders(DownloadPluginHTTPException.CODE, "Found", head, e.mimeType, e.data.length);
				ctx.writeData(e.data);
			}
			catch(PluginHTTPException e)
			{
				sendErrorPage(ctx, PluginHTTPException.code, e.message, e.location);
			}
			catch(Throwable t)
			{
				writeInternalError(t, ctx);
			}
		}
		else
		{
			PageMaker pageMaker = ctx.getPageMaker();
			
			if (request.isPartSet("submit-official")) {
				final String pluginName = request.getPartAsString("plugin-name", 40);
				node.executor.execute(new Runnable() {

					public void run() {
						pm.startPluginOfficial(pluginName, true, true, "https".equals(request.getPartAsString("pluginSource", 10)));
					}
					
				});
				
				headers.put("Location", ".");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
			if (request.isPartSet("submit-other")) {
				String pluginName = null;
				pluginName = request.getPartAsString("plugin-url", 200);
				boolean fileonly = "on".equalsIgnoreCase(request.getPartAsString("fileonly", 20));
				if (fileonly) 
					pm.startPluginFile(pluginName, true);
				else
					pm.startPluginURL(pluginName, true);
				headers.put("Location", ".");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
			if (request.isPartSet("submit-freenet")) {
				String pluginName = null;
				pluginName = request.getPartAsString("plugin-uri", 300);
				pm.startPluginFreenet(pluginName, true);
				headers.put("Location", ".");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
			if (request.isPartSet("cancel")){
				headers.put("Location", "/plugins/");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				return;
			}
			if (request.getPartAsString("unloadconfirm", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
				String pluginThreadName = request.getPartAsString("unloadconfirm", MAX_PLUGIN_NAME_LENGTH);
				String pluginSpecification = getPluginSpecification(pm, pluginThreadName);
				pm.killPlugin(pluginThreadName, MAX_THREADED_UNLOAD_WAIT_TIME, false);
				if (request.isPartSet("purge")) {
					pm.removeCachedCopy(pluginSpecification);
				}
				PageNode page = pageMaker.getPageNode(l10n("plugins"), ctx);
				HTMLNode pageNode = page.outer;
				HTMLNode contentNode = page.content;
				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-success");
				infobox.addChild("div", "class", "infobox-header", l10n("pluginUnloaded"));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addChild("#", l10n("pluginUnloadedWithName", "name", pluginThreadName));
				infoboxContent.addChild("br");
				infoboxContent.addChild("a", "href", "/plugins/", l10n("returnToPluginPage"));
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			}if (request.getPartAsString("unload", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
				PageNode page = pageMaker.getPageNode(l10n("plugins"), ctx);
				HTMLNode pageNode = page.outer;
				HTMLNode contentNode = page.content;
				HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-query");
				infobox.addChild("div", "class", "infobox-header", l10n("unloadPluginTitle"));
				HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
				infoboxContent.addChild("#", l10n("unloadPluginWithName", "name", request.getPartAsString("unload", MAX_PLUGIN_NAME_LENGTH)));
				HTMLNode unloadForm = 
					ctx.addFormChild(infoboxContent, "/plugins/", "unloadPluginConfirmForm");
				unloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "unloadconfirm", request.getPartAsString("unload", MAX_PLUGIN_NAME_LENGTH) });
				HTMLNode tempNode = unloadForm.addChild("p");
				tempNode.addChild("input", new String[] { "type", "name" }, new String[] { "checkbox", "purge" });
				tempNode.addChild("#", l10n("unloadPurge"));
				tempNode = unloadForm.addChild("p");
				tempNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "confirm", l10n("unload") });
				tempNode.addChild("#", " ");
				tempNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel") });
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			} else if (request.getPartAsString("reload", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
				PageNode page = pageMaker.getPageNode(l10n("plugins"), ctx);
				HTMLNode pageNode = page.outer;
				HTMLNode contentNode = page.content;
				HTMLNode reloadContent = pageMaker.getInfobox("infobox infobox-query", l10n("reloadPluginTitle"), contentNode, "plugin-reload", true);
				reloadContent.addChild("p", l10n("reloadExplanation"));
				reloadContent.addChild("p", l10n("reloadWarning"));
				HTMLNode reloadForm = ctx.addFormChild(reloadContent, "/plugins/", "reloadPluginConfirmForm");
				reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "reloadconfirm", request.getPartAsString("reload", MAX_PLUGIN_NAME_LENGTH) });
				HTMLNode tempNode = reloadForm.addChild("p");
				tempNode.addChild("input", new String[] { "type", "name" }, new String[] { "checkbox", "purge" });
				tempNode.addChild("#", l10n("reloadPurgeWarning"));
				tempNode = reloadForm.addChild("p");
				tempNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "confirm", l10n("reload") });
				tempNode.addChild("#", " ");
				tempNode.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "cancel", NodeL10n.getBase().getString("Toadlet.cancel") });
				
				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
				return;
			} else if (request.getPartAsString("update", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
				// Deploy the plugin update
				final String pluginFilename = request.getPartAsString("update", MAX_PLUGIN_NAME_LENGTH);

				if (!pm.isPluginLoaded(pluginFilename)) {
					sendErrorPage(ctx, 404, l10n("pluginNotFoundUpdatingTitle"), 
							l10n("pluginNotFoundUpdating", "name", pluginFilename));
				} else {
					node.nodeUpdater.deployPluginWhenReady(pluginFilename);

					headers.put("Location", ".");
					ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				}
				return;
				
			}else if (request.getPartAsString("reloadconfirm", MAX_PLUGIN_NAME_LENGTH).length() > 0) {
				boolean purge = request.isPartSet("purge");
				String pluginThreadName = request.getPartAsString("reloadconfirm", MAX_PLUGIN_NAME_LENGTH);
				final String fn = getPluginSpecification(pm, pluginThreadName);

				if (fn == null) {
					sendErrorPage(ctx, 404, l10n("pluginNotFoundReloadTitle"), 
							l10n("pluginNotFoundReload"));
				} else {
					pm.killPlugin(pluginThreadName, MAX_THREADED_UNLOAD_WAIT_TIME, true);
					if (purge) {
						pm.removeCachedCopy(fn);
					}
					node.executor.execute(new Runnable() {

						public void run() {
							// FIXME
							pm.startPluginAuto(fn, true);
						}
						
					});

					headers.put("Location", ".");
					ctx.sendReplyHeaders(302, "Found", headers, null, 0);
				}
				return;
			}else {
				// Ignore
				headers.put("Location", ".");
				ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			}

		}

	}

	/**
	 * Searches all plugins for the plugin with the given thread name and
	 * returns the plugin specification used to load the plugin.
	 * 
	 * @param pluginManager
	 *            The plugin manager
	 * @param pluginThreadName
	 *            The thread name of the plugin
	 * @return The plugin specification of the plugin, or <code>null</code> if
	 *         no plugin was found
	 */
	private String getPluginSpecification(PluginManager pluginManager, String pluginThreadName) {
		Iterator<PluginInfoWrapper> it = pluginManager.getPlugins().iterator();
		while (it.hasNext()) {
			PluginInfoWrapper pi = it.next();
			if (pi.getThreadName().equals(pluginThreadName)) {
				return pi.getFilename();
			}
		}
		return null;
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("PproxyToadlet."+key, new String[] { pattern }, new String[] { value });
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("PproxyToadlet."+key);
	}

	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx)
	throws ToadletContextClosedException, IOException {

		//String basepath = "/plugins/";
		String path = request.getPath();

		// remove leading / and plugins/ from path
		if(path.startsWith("/")) path = path.substring(1);
		if(path.startsWith("plugins/")) path = path.substring("plugins/".length());

		PluginManager pm = node.pluginManager;

		if(logMINOR)
			Logger.minor(this, "Pproxy fetching "+path);
		try {
			if (path.equals("")) {
				if (!ctx.isAllowedFullAccess()) {
					super.sendErrorPage(ctx, 403, "Unauthorized", NodeL10n.getBase().getString("Toadlet.unauthorized"));
					return;
				}
				
				final int mode = ctx.getPageMaker().parseMode(request, this.container);
				boolean advancedModeEnabled = (mode >= PageMaker.MODE_ADVANCED);

				Iterator<PluginProgress> loadingPlugins = pm.getStartingPlugins().iterator();

				PageNode page = ctx.getPageMaker().getPageNode(l10n("pluginsWithNodeName", "name", core.getMyName()), ctx);
				HTMLNode pageNode = page.outer;
				if (loadingPlugins.hasNext()) {
					/* okay, add a refresh. */
					page.headNode.addChild("meta", new String[] { "http-equiv", "content" }, new String[] { "refresh", "10; url=" });
				}
				HTMLNode contentNode = page.content;

				contentNode.addChild(core.alerts.createSummary());

				/* find which plugins have already been loaded. */
				List<OfficialPluginDescription> availablePlugins = pm.findAvailablePlugins();
				Iterator<PluginInfoWrapper> loadedPlugins = pm.getPlugins().iterator();
				while (loadedPlugins.hasNext()) {
					PluginInfoWrapper pluginInfoWrapper = loadedPlugins.next();
					String pluginName = pluginInfoWrapper.getPluginClassName();
					String shortPluginName = pluginName.substring(pluginName.lastIndexOf('.') + 1);
					availablePlugins.remove(shortPluginName);

					/* FIXME: Workaround the "Freemail" plugin show duplicate problem
					 * The "Freemail" plugin is show on "Aviliable Plugin" even
					 * if it is loaded. However fixing the plugin itself may break
					 * running it as standalone application. */
					if (shortPluginName.equals("FreemailPlugin")) availablePlugins.remove("Freemail"); // DOH!
				}
				while (loadingPlugins.hasNext()) {
					PluginProgress pluginProgress = loadingPlugins.next();
					String pluginName = pluginProgress.getName();
					availablePlugins.remove(pluginName);
				}

				showStartingPlugins(pm, contentNode);
				showPluginList(ctx, pm, contentNode, advancedModeEnabled);
				showOfficialPluginLoader(ctx, contentNode, availablePlugins, pm, advancedModeEnabled);
				showUnofficialPluginLoader(ctx, contentNode);
				showFreenetPluginLoader(ctx, contentNode);

				writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			} else {
				// split path into plugin class name and 'data' path for plugin
				int to = path.indexOf("/");
				String plugin;
				if (to == -1) {
					plugin = path;
				} else {
					plugin = path.substring(0, to);
				}

				// Plugin may need to know where it was accessed from, so it can e.g. produce relative URLs.
				//writeReply(ctx, 200, "text/html", "OK", mkPage("plugin", pm.handleHTTPGet(plugin, data)));
				writeHTMLReply(ctx, 200, "OK", pm.handleHTTPGet(plugin, request));				
			}

			//FetchResult result = fetch(key);
			//writeReply(ctx, 200, result.getMimeType(), "OK", result.asBucket());
		} catch (RedirectPluginHTTPException e) {
			writeTemporaryRedirect(ctx, e.message, e.newLocation);
		} catch (NotFoundPluginHTTPException e) {
			sendErrorPage(ctx, NotFoundPluginHTTPException.code, e.message, e.location);
		} catch (AccessDeniedPluginHTTPException e) {
			sendErrorPage(ctx, AccessDeniedPluginHTTPException.code, e.message, e.location);
		} catch (DownloadPluginHTTPException e) {
			// FIXME: maybe it ought to be defined like sendErrorPage : in toadlets

			MultiValueTable<String, String> head = new MultiValueTable<String, String>();
			head.put("Content-Disposition", "attachment; filename=\"" + e.filename + '"');
			ctx.sendReplyHeaders(DownloadPluginHTTPException.CODE, "Found", head, e.mimeType, e.data.length);
			ctx.writeData(e.data);
		} catch(PluginHTTPException e) {
			sendErrorPage(ctx, PluginHTTPException.code, e.message, e.location);
		} catch (Throwable t) {
			ctx.forceDisconnect();
			writeInternalError(t, ctx);
		}
	}

	/**
	 * Shows a list of all currently loading plugins.
	 * 
	 * @param pluginManager
	 *            The plugin manager
	 * @param contentNode
	 *            The node to add content to
	 */
	private void showStartingPlugins(PluginManager pluginManager, HTMLNode contentNode) {
		Set<PluginProgress> startingPlugins = pluginManager.getStartingPlugins();
		if (!startingPlugins.isEmpty()) {
			HTMLNode startingPluginsBox = contentNode.addChild("div", "class", "infobox infobox-normal");
			startingPluginsBox.addChild("div", "class", "infobox-header", l10n("startingPluginsTitle"));
			HTMLNode startingPluginsContent = startingPluginsBox.addChild("div", "class", "infobox-content");
			HTMLNode startingPluginsTable = startingPluginsContent.addChild("table");
			HTMLNode startingPluginsHeader = startingPluginsTable.addChild("tr");
			startingPluginsHeader.addChild("th", l10n("startingPluginName"));
			startingPluginsHeader.addChild("th", l10n("startingPluginStatus"));
			startingPluginsHeader.addChild("th", l10n("startingPluginTime"));
			Iterator<PluginProgress> startingPluginsIterator = startingPlugins.iterator();
			while (startingPluginsIterator.hasNext()) {
				PluginProgress pluginProgress = startingPluginsIterator.next();
				HTMLNode startingPluginsRow = startingPluginsTable.addChild("tr");
				startingPluginsRow.addChild("td", pluginProgress.getName());
				startingPluginsRow.addChild(pluginProgress.toLocalisedHTML());
				startingPluginsRow.addChild("td", "aligh", "right", TimeUtil.formatTime(pluginProgress.getTime()));
			}
		}
	}

	private void showPluginList(ToadletContext ctx, PluginManager pm, HTMLNode contentNode, boolean advancedMode) throws ToadletContextClosedException, IOException {
		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		infobox.addChild("div", "class", "infobox-header", NodeL10n.getBase().getString("PluginToadlet.pluginListTitle"));
		HTMLNode infoboxContent = infobox.addChild("div", "class", "infobox-content");
		if (pm.getPlugins().isEmpty()) {
			infoboxContent.addChild("div", l10n("noPlugins"));
		} else {
			HTMLNode pluginTable = infoboxContent.addChild("table", "class", "plugins");
			HTMLNode headerRow = pluginTable.addChild("tr");
			headerRow.addChild("th", l10n("pluginFilename"));
			if(advancedMode)
				headerRow.addChild("th", l10n("classNameTitle"));
			headerRow.addChild("th", l10n("versionTitle"));
			if(advancedMode) {
				headerRow.addChild("th", l10n("internalIDTitle"));
				headerRow.addChild("th", l10n("startedAtTitle"));
			}
			headerRow.addChild("th");
			headerRow.addChild("th");
			headerRow.addChild("th");
			Iterator<PluginInfoWrapper> it = pm.getPlugins().iterator();
			while (it.hasNext()) {
				PluginInfoWrapper pi = it.next();
				HTMLNode pluginRow = pluginTable.addChild("tr");
				pluginRow.addChild("td", pi.getFilename());
				if(advancedMode)
					pluginRow.addChild("td", pi.getPluginClassName());
				long ver = pi.getPluginLongVersion();
				if(ver != -1)
					pluginRow.addChild("td", pi.getPluginVersion()+" ("+ver+")");
				else
					pluginRow.addChild("td", pi.getPluginVersion());
				if(advancedMode) {
					pluginRow.addChild("td", pi.getThreadName());
					pluginRow.addChild("td", new Date(pi.getStarted()).toString());
				}
				if (pi.isStopping()) {
					pluginRow.addChild("td", l10n("pluginStopping"));
					/* add two empty cells. */
					pluginRow.addChild("td");
					pluginRow.addChild("td");
				} else {
					if (pi.isPproxyPlugin()) {
						HTMLNode visitForm = pluginRow.addChild("td").addChild("form", new String[] { "method", "action", "target" }, new String[] { "get", pi.getPluginClassName(), "_blank" });
						visitForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "formPassword", core.formPassword });
						visitForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", NodeL10n.getBase().getString("PluginToadlet.visit") });
					} else
						pluginRow.addChild("td");
					HTMLNode unloadForm = ctx.addFormChild(pluginRow.addChild("td"), ".", "unloadPluginForm");
					unloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "unload", pi.getThreadName() });
					unloadForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("unload") });
					HTMLNode reloadForm = ctx.addFormChild(pluginRow.addChild("td"), ".", "reloadPluginForm");
					reloadForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "hidden", "reload", pi.getThreadName() });
					reloadForm.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("reload") });
				}
			}
		}
	}
	
	private void showOfficialPluginLoader(ToadletContext toadletContext, HTMLNode contentNode, List<OfficialPluginDescription> availablePlugins, PluginManager pm, boolean advancedModeEnabled) {
		/* box for "official" plugins. */
		HTMLNode addOfficialPluginBox = contentNode.addChild("div", "class", "infobox infobox-normal");
		addOfficialPluginBox.addChild("div", "class", "infobox-header", l10n("loadOfficialPlugin"));
		HTMLNode addOfficialPluginContent = addOfficialPluginBox.addChild("div", "class", "infobox-content");
		HTMLNode addOfficialForm = toadletContext.addFormChild(addOfficialPluginContent, ".", "addOfficialPluginForm");
		
		HTMLNode p = addOfficialForm.addChild("p");
		
		p.addChild("#", l10n("loadOfficialPluginText"));
		
		// Over Freenet or over HTTP??
		
		p.addChild("#", " " + l10n("pluginSourceChoice"));
		
		boolean isLowSecLevel = pm.loadOfficialPluginsFromWeb();
		
		HTMLNode input = addOfficialForm.addChild("input", new String[] { "type", "name", "value" },
				new String[] { "radio", "pluginSource", "freenet" });
		if(!isLowSecLevel)
			input.addAttribute("checked", "true");
		addOfficialForm.addChild("#", l10n("pluginSourceFreenet"));
		addOfficialForm.addChild("br");
		input = addOfficialForm.addChild("input", new String[] { "type", "name", "value" },
				new String[] { "radio", "pluginSource", "https" });
		if(isLowSecLevel)
			input.addAttribute("checked", "true");
		addOfficialForm.addChild("#", l10n("pluginSourceHTTPS"));
		addOfficialForm.addChild("#", " ");
		if(node.getOpennet() == null)
			addOfficialForm.addChild("b").addChild("font", "color", "red", l10n("pluginSourceHTTPSWarningDarknet"));
		else
			// FIXME CSS-ize this
			addOfficialForm.addChild("b", l10n("pluginSourceHTTPSWarning"));
		
		p = addOfficialForm.addChild("p");
		
		p.addChild("#", (l10n("loadOfficialPluginLabel") + ": "));
		Iterator<OfficialPluginDescription> availablePluginIterator = availablePlugins.iterator();
		boolean first = true;
		while (availablePluginIterator.hasNext()) {
			OfficialPluginDescription plugin = availablePluginIterator.next();
			String pluginName = plugin.name;
			if(!pm.isPluginLoaded(pluginName)) {
				if(!advancedModeEnabled) {
					if(plugin.advanced || plugin.deprecated || plugin.experimental)
						continue;
				}
				HTMLNode option;
				if(first) {
					option = addOfficialForm;
					first = false;
				} else {
					option = addOfficialForm.addChild("br");
				}
				option = option.addChild("input", new String[] { "type", "name", "value" },
						new String[] { "radio", "plugin-name", pluginName });
				option.addChild("i", pluginName);
				if(plugin.deprecated)
					option.addChild("b", " ("+l10n("loadLabelDeprecated")+")");
				if(plugin.experimental)
					option.addChild("b", " ("+l10n("loadLabelExperimental")+")");
				option.addChild("#", " - "+l10n("pluginDesc."+pluginName));
			}
		}
		addOfficialForm.addChild("p").addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit-official", l10n("Load") });
	}
	
	private void showUnofficialPluginLoader(ToadletContext toadletContext, HTMLNode contentNode) {
		/* box for unofficial plugins. */
		HTMLNode addOtherPluginBox = contentNode.addChild("div", "class", "infobox infobox-normal");
		addOtherPluginBox.addChild("div", "class", "infobox-header", l10n("loadOtherPlugin"));
		HTMLNode addOtherPluginContent = addOtherPluginBox.addChild("div", "class", "infobox-content");
		HTMLNode addOtherForm = toadletContext.addFormChild(addOtherPluginContent, ".", "addOtherPluginForm");
		addOtherForm.addChild("div", l10n("loadOtherPluginText"));
		addOtherForm.addChild("#", (l10n("loadOtherURLLabel") + ": "));
		addOtherForm.addChild("input", new String[] { "type", "name", "size" }, new String[] { "text", "plugin-url", "80" });
		addOtherForm.addChild("#", " ");
		addOtherForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit-other", l10n("Load") });
		addOtherForm.addChild("br");
		addOtherForm.addChild("input", new String[] { "type", "name", "checked" }, new String[] { "checkbox", "fileonly", "checked" });
		addOtherForm.addChild("#", " " + l10n("fileonly"));
	}
	
	private void showFreenetPluginLoader(ToadletContext toadletContext, HTMLNode contentNode) {
		/* box for freenet plugins. */
		HTMLNode addFreenetPluginBox = contentNode.addChild("div", "class", "infobox infobox-normal");
		addFreenetPluginBox.addChild("div", "class", "infobox-header", l10n("loadFreenetPlugin"));
		HTMLNode addFreenetPluginContent = addFreenetPluginBox.addChild("div", "class", "infobox-content");
		HTMLNode addFreenetForm = toadletContext.addFormChild(addFreenetPluginContent, ".", "addFreenetPluginForm");
		addFreenetForm.addChild("div", l10n("loadFreenetPluginText"));
		addFreenetForm.addChild("#", (l10n("loadFreenetURLLabel") + ": "));
		addFreenetForm.addChild("input", new String[] { "type", "name", "size" }, new String[] { "text", "plugin-uri", "80" });
		addFreenetForm.addChild("#", " ");
		addFreenetForm.addChild("input", new String[] { "type", "name", "value" }, new String[] { "submit", "submit-freenet", l10n("Load") });
	}

	@Override
	public String path() {
		return PATH;
	}
	
	public static final String PATH = "/plugins/";

}
