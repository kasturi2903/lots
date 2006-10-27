/*
 * Dateiname: WollMuxSingleton.java
 * Projekt  : WollMux
 * Funktion : Singleton f�r zentrale WollMux-Methoden.
 * 
 * Copyright: Landeshauptstadt M�nchen
 *
 * �nderungshistorie:
 * Datum      | Wer | �nderungsgrund
 * -------------------------------------------------------------------
 * 14.10.2005 | LUT | Erstellung
 * 09.11.2005 | LUT | + Logfile wird jetzt erweitert (append-modus)
 *                    + verwenden des Konfigurationsparameters SENDER_SOURCE
 *                    + Erster Start des wollmux �ber wm_configured feststellen.
 * 05.12.2005 | BNK | line.separator statt \n     
 * 13.04.2006 | BNK | .wollmux/ Handling ausgegliedert in WollMuxFiles.
 * 20.04.2006 | LUT | �berarbeitung Code-Kommentare  
 * 20.04.2006 | BNK | DEFAULT_CONTEXT ausgegliedert nach WollMuxFiles
 * 21.04.2006 | LUT | + Robusteres Verhalten bei Fehlern w�hrend dem Einlesen 
 *                    von Konfigurationsdateien; 
 *                    + wohldefinierte Datenstrukturen
 *                    + Flag f�r EventProcessor: acceptEvents
 * 08.05.2006 | LUT | + isDebugMode()
 * 10.05.2006 | BNK | +parseGlobalFunctions()
 *                  | +parseFunctionDialogs()
 * 26.05.2006 | BNK | DJ initialisierung ausgelagert nacht WollMuxFiles
 * 06.06.2006 | LUT | + Abl�sung der Event-Klasse durch saubere Objektstruktur
 * -------------------------------------------------------------------
 *
 * @author Christoph Lutz (D-III-ITD 5.1)
 * @version 1.0
 * 
 */

package de.muenchen.allg.itd51.wollmux;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;

import com.sun.star.frame.XDispatch;
import com.sun.star.frame.XDispatchProvider;
import com.sun.star.frame.XModel;
import com.sun.star.lang.EventObject;
import com.sun.star.lang.XComponent;
import com.sun.star.text.XTextDocument;
import com.sun.star.uno.Exception;
import com.sun.star.uno.UnoRuntime;
import com.sun.star.uno.XComponentContext;
import com.sun.star.util.XURLTransformer;

import de.muenchen.allg.afid.UNO;
import de.muenchen.allg.afid.UnoService;
import de.muenchen.allg.itd51.parser.ConfigThingy;
import de.muenchen.allg.itd51.parser.NodeNotFoundException;
import de.muenchen.allg.itd51.wollmux.db.DJDataset;
import de.muenchen.allg.itd51.wollmux.db.DJDatasetListElement;
import de.muenchen.allg.itd51.wollmux.db.DatasetNotFoundException;
import de.muenchen.allg.itd51.wollmux.db.DatasourceJoiner;
import de.muenchen.allg.itd51.wollmux.db.QueryResults;
import de.muenchen.allg.itd51.wollmux.dialog.DialogLibrary;
import de.muenchen.allg.itd51.wollmux.func.FunctionLibrary;
import de.muenchen.allg.itd51.wollmux.func.PrintFunctionLibrary;

/**
 * Diese Klasse ist ein Singleton, welcher den WollMux initialisiert und alle
 * zentralen WollMux-Methoden zur Verf�gung stellt. Selbst der WollMux-Service
 * de.muenchen.allg.itd51.wollmux.comp.WollMux, der fr�her zentraler Anlaufpunkt
 * war, bedient sich gr��tenteils aus den zentralen Methoden des Singletons.
 */
public class WollMuxSingleton implements XPALProvider
{

  private static WollMuxSingleton singletonInstance = null;

  /**
   * Enth�lt die geparste Textfragmentliste, die in der wollmux.conf definiert
   * wurde.
   */
  private VisibleTextFragmentList textFragmentList;

  /**
   * Enth�lt die im Funktionen-Abschnitt der wollmux,conf definierten
   * Funktionen.
   */
  private FunctionLibrary globalFunctions;

  /**
   * Enth�lt die im Funktionsdialoge-Abschnitt der wollmux,conf definierten
   * Dialoge.
   */
  private DialogLibrary funcDialogs;

  /**
   * Enth�lt die im Funktionen-Abschnitt der wollmux,conf definierten
   * Funktionen.
   */
  private PrintFunctionLibrary globalPrintFunctions;

  /**
   * Enth�lt den default XComponentContext in dem der WollMux (bzw. das OOo)
   * l�uft.
   */
  private XComponentContext ctx;

  /**
   * Enth�lt alle registrierten SenderBox-Objekte.
   */
  private Vector registeredPALChangeListener;

  /**
   * Enth�lt eine Zuordnung von HashableComponent Objekten, die die
   * XTextDocumente repr�sentieren, auf die zugeh�rigen TextDocumentModels
   */
  private HashMap currentTextDocumentModels;

  /**
   * Die WollMux-Hauptklasse ist als singleton realisiert.
   */
  private WollMuxSingleton(XComponentContext ctx)
  {
    // Der XComponentContext wir hier gesichert und vom WollMuxSingleton mit
    // getXComponentContext zur�ckgeliefert.
    this.ctx = ctx;

    this.currentTextDocumentModels = new HashMap();

    // Initialisiere die UNO-Klasse, so dass auch mit dieser Hilfsklasse
    // gearbeitet werden kann.
    try
    {
      UNO.init(ctx.getServiceManager());
    }
    catch (java.lang.Exception e)
    {
      Logger.error(e);
    }

    boolean successfulStartup = true;

    registeredPALChangeListener = new Vector();

    WollMuxFiles.setupWollMuxDir();

    Logger.debug("StartupWollMux");
    Logger.debug("Build-Info: " + getBuildInfo());
    Logger.debug("wollmuxConfFile = "
                 + WollMuxFiles.getWollMuxConfFile().toString());
    Logger.debug("DEFAULT_CONTEXT \""
                 + WollMuxFiles.getDEFAULT_CONTEXT().toString()
                 + "\"");
    Logger.debug("CONF_VERSION: " + getConfVersionInfo());

    // VisibleTextFragmentList erzeugen
    textFragmentList = new VisibleTextFragmentList(WollMuxFiles
        .getWollmuxConf());

    // Versuchen, den DJ zu initialisieren und Flag setzen, falls nicht
    // erfolgreich.
    if (getDatasourceJoiner() == null) successfulStartup = false;

    /*
     * Globale Funktionsdialoge parsen. ACHTUNG! Muss vor parseGlobalFunctions()
     * erfolgen. Als context wird null �bergeben, weil globale Funktionen keinen
     * Kontext haben. TODO �berlegen, ob ein globaler Kontext doch Sinn machen
     * k�nnte. Dadurch k�nnten globale Funktionen globale Funktionsdialoge
     * darstellen, die global einheitliche Werte haben.
     */
    funcDialogs = WollMuxFiles.parseFunctionDialogs(WollMuxFiles
        .getWollmuxConf(), null, null);

    /*
     * Globale Funktionen parsen. ACHTUNG! Verwendet die Funktionsdialoge. Diese
     * m�ssen also vorher geparst sein. Als context wird null �bergeben, weil
     * globale Funktionen keinen Kontext haben.
     */
    globalFunctions = WollMuxFiles.parseFunctions(
        WollMuxFiles.getWollmuxConf(),
        getFunctionDialogs(),
        null,
        null);

    /*
     * Globale Druckfunktionen parsen.
     */
    globalPrintFunctions = WollMuxFiles.parsePrintFunctions(WollMuxFiles
        .getWollmuxConf());

    // Initialisiere EventProcessor
    WollMuxEventHandler.setAcceptEvents(successfulStartup);

    // register global EventListener
    try
    {
      UnoService eventBroadcaster = UnoService.createWithContext(
          "com.sun.star.frame.GlobalEventBroadcaster",
          ctx);
      eventBroadcaster.xEventBroadcaster().addEventListener(
          new GlobalEventListener());
    }
    catch (Exception e)
    {
      Logger.error(e);
    }
  }

  /**
   * Diese Methode liefert die Instanz des WollMux-Singletons. Ist der WollMux
   * noch nicht initialisiert, so liefert die Methode null!
   * 
   * @return Instanz des WollMuxSingletons oder null.
   */
  public static WollMuxSingleton getInstance()
  {
    return singletonInstance;
  }

  /**
   * Diese Methode initialisiert das WollMuxSingleton (nur dann, wenn es noch
   * nicht initialisiert wurde)
   */
  public static void initialize(XComponentContext ctx)
  {
    if (singletonInstance == null)
    {
      singletonInstance = new WollMuxSingleton(ctx);

      // Event ON_FIRST_INITIALIZE erzeugen:
      WollMuxEventHandler.handleInitialize();
    }
  }

  /**
   * Diese Methode liefert die erste Zeile aus der buildinfo-Datei der aktuellen
   * WollMux-Installation zur�ck. Der Build-Status wird w�hrend dem
   * Build-Prozess mit dem Kommando "svn info" auf das Projektverzeichnis
   * erstellt. Die Buildinfo-Datei buildinfo enth�lt die Paketnummer und die
   * svn-Revision und ist im WollMux.uno.pkg-Paket sowie in der
   * WollMux.uno.jar-Datei abgelegt.
   * 
   * Kann dieses File nicht gelesen werden, so wird eine entsprechende
   * Ersatzmeldung erzeugt (siehe Sourcecode).
   * 
   * @return Der Build-Status der aktuellen WollMux-Installation.
   */
  public String getBuildInfo()
  {
    try
    {
      URL url = WollMuxSingleton.class.getClassLoader()
          .getResource("buildinfo");
      if (url != null)
      {
        BufferedReader in = new BufferedReader(new InputStreamReader(url
            .openStream()));
        return in.readLine().toString();
      }
    }
    catch (java.lang.Exception x)
    {
    }
    return "Version: unbekannt";
  }

  /**
   * Diese Methode liefert die Versionsinformation der aktuell verwendeten
   * wollmux-Konfiguration (z.B. "wollmux-standard-config-2.2.1") als String
   * zur�ck, wenn in der Konfiguration ein entsprechender CONF_VERSION-Schl�ssel
   * definiert ist, oder "unbekannt", falls der dieser Schl�ssel nicht
   * existiert.
   * 
   * @return Der Versionsinformation der aktuellen WollMux-Konfiguration (falls
   *         definiert) oder "unbekannt", falls nicht.
   */
  public String getConfVersionInfo()
  {
    ConfigThingy versions = getWollmuxConf().query("CONF_VERSION");
    try
    {
      return versions.getLastChild().toString();
    }
    catch (NodeNotFoundException e)
    {
      return "unbekannt";
    }
  }

  /**
   * @return Returns the textFragmentList.
   */
  public VisibleTextFragmentList getTextFragmentList()
  {
    return textFragmentList;
  }

  /**
   * @return Returns the xComponentContext.
   */
  public XComponentContext getXComponentContext()
  {
    return ctx;
  }

  /**
   * Diese Methode liefert eine Instanz auf den aktuellen DatasourceJoiner
   * zur�ck.
   * 
   * @return Returns the datasourceJoiner.
   */
  public DatasourceJoiner getDatasourceJoiner()
  {
    return WollMuxFiles.getDatasourceJoiner();
  }

  /**
   * Diese Methode registriert einen XPALChangeEventListener, der updates
   * empf�ngt wenn sich die PAL �ndert. Die Methode ignoriert alle
   * XPALChangeEventListenener-Instanzen, die bereits registriert wurden.
   * Mehrfachregistrierung der selben Instanz ist also nicht m�glich.
   * 
   * Achtung: Die Methode darf nicht direkt von einem UNO-Service aufgerufen
   * werden, sondern jeder Aufruf muss �ber den EventHandler laufen. Deswegen
   * exportiert WollMuxSingleton auch nicht das
   * XPALChangedBroadcaster-Interface.
   */
  public void addPALChangeEventListener(XPALChangeEventListener listener)
  {
    Logger.debug2("WollMuxSingleton::addPALChangeEventListener()");

    if (listener == null) return;

    Iterator i = registeredPALChangeListener.iterator();
    while (i.hasNext())
    {
      Object l = i.next();
      if (UnoRuntime.areSame(l, listener)) return;
    }
    registeredPALChangeListener.add(listener);
  }

  /**
   * Diese Methode deregistriert einen XPALChangeEventListener wenn er bereits
   * registriert war.
   * 
   * Achtung: Die Methode darf nicht direkt von einem UNO-Service aufgerufen
   * werden, sondern jeder Aufruf muss �ber den EventHandler laufen. Deswegen
   * exportiert WollMuxSingleton auch nicht das
   * XPALChangedBroadcaster-Interface.
   */
  public void removePALChangeEventListener(XPALChangeEventListener listener)
  {
    Logger.debug2("WollMuxSingleton::removePALChangeEventListener()");
    Iterator i = registeredPALChangeListener.iterator();
    while (i.hasNext())
    {
      Object l = i.next();
      if (UnoRuntime.areSame(l, listener)) i.remove();
    }
  }

  /**
   * Liefert einen Iterator auf alle registrierten SenderBox-Objekte.
   * 
   * @return Iterator auf alle registrierten SenderBox-Objekte.
   */
  public Iterator palChangeListenerIterator()
  {
    return registeredPALChangeListener.iterator();
  }

  /**
   * Diese Methode liefert eine alphabethisch aufsteigend sortierte Liste aller
   * Eintr�ge der Pers�nlichen Absenderliste (PAL) in einem String-Array, wobei
   * die einzelnen Eintr�ge in der Form "<Nachname>, <Vorname> (<Rolle>)"
   * sind.
   * 
   * @see de.muenchen.allg.itd51.wollmux.XPALProvider#getPALEntries()
   */
  public String[] getPALEntries()
  {
    DJDatasetListElement[] pal = getSortedPALEntries();
    String[] elements = new String[pal.length];
    for (int i = 0; i < pal.length; i++)
    {
      elements[i] = pal[i].toString();
    }
    return elements;
  }

  /**
   * Diese Methode liefert alle DJDatasetListElemente der Pers�nlichen
   * Absenderliste (PAL) in alphabetisch aufsteigend sortierter Reihenfolge.
   * 
   * @return alle DJDatasetListElemente der Pers�nlichen Absenderliste (PAL) in
   *         alphabetisch aufsteigend sortierter Reihenfolge.
   */
  public DJDatasetListElement[] getSortedPALEntries()
  {
    // Liste der entries aufbauen.
    QueryResults data = getDatasourceJoiner().getLOS();

    DJDatasetListElement[] elements = new DJDatasetListElement[data.size()];
    Iterator iter = data.iterator();
    int i = 0;
    while (iter.hasNext())
      elements[i++] = new DJDatasetListElement((DJDataset) iter.next());
    Arrays.sort(elements);

    return elements;
  }

  /**
   * Diese Methode liefert den aktuell aus der pers�nlichen Absenderliste (PAL)
   * ausgew�hlten Absender im Format "<Nachname>, <Vorname> (<Rolle>)" zur�ck.
   * Ist die PAL leer oder noch kein Absender ausgew�hlt, so liefert die Methode
   * den Leerstring "" zur�ck. Dieser Sonderfall sollte nat�rlich entsprechend
   * durch die aufrufende Methode behandelt werden.
   * 
   * @see de.muenchen.allg.itd51.wollmux.XPALProvider#getCurrentSender()
   * 
   * @return den aktuell aus der PAL ausgew�hlten Absender als String. Ist kein
   *         Absender ausgew�hlt wird der Leerstring "" zur�ckgegeben.
   */
  public String getCurrentSender()
  {
    try
    {
      DJDataset selected = getDatasourceJoiner().getSelectedDataset();
      return new DJDatasetListElement(selected).toString();
    }
    catch (DatasetNotFoundException e)
    {
      return "";
    }
  }

  /**
   * siehe {@link WollMuxFiles#getWollmuxConf()}.
   * 
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  public ConfigThingy getWollmuxConf()
  {
    return WollMuxFiles.getWollmuxConf();
  }

  /**
   * siehe {@link WollMuxFiles#getDEFAULT_CONTEXT()}.
   * 
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  public URL getDEFAULT_CONTEXT()
  {
    return WollMuxFiles.getDEFAULT_CONTEXT();
  }

  /**
   * Liefert die Funktionsbibliothek, die die global definierten Funktionen
   * enth�lt.
   * 
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  public FunctionLibrary getGlobalFunctions()
  {
    return globalFunctions;
  }

  /**
   * Liefert die Funktionsbibliothek, die die global definierten Druckfunktionen
   * enth�lt.
   * 
   * @author Christoph Lutz (D-III-ITD 5.1)
   */
  public PrintFunctionLibrary getGlobalPrintFunctions()
  {
    return globalPrintFunctions;
  }

  /**
   * Liefert die Dialogbibliothek, die die Dialoge enth�lt, die in Funktionen
   * (Grundfunktion "DIALOG") verwendung finden.
   * 
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  public DialogLibrary getFunctionDialogs()
  {
    return funcDialogs;
  }

  /**
   * siehe {@link WollMuxFiles#isDebugMode()}.
   * 
   * @author Christoph Lutz (D-III-ITD 5.1)
   */
  public boolean isDebugMode()
  {
    return WollMuxFiles.isDebugMode();
  }

  /**
   * Liefert true, wenn f�r das XTextDocument doc ein TextDocumentModel im
   * WollMuxSingleton registriert ist, ansonsten false.
   * 
   * @param doc
   *          das Dokument f�r das ein TextDocumentModel gesucht wird.
   * @return true, wenn f�r das XTextDocument doc ein TextDocumentModel im
   *         WollMuxSingleton registriert ist, ansonsten false.
   */
  public boolean hasTextDocumentModel(XTextDocument doc)
  {
    HashableComponent key = new HashableComponent(doc);

    return currentTextDocumentModels.containsKey(key);
  }

  /**
   * Liefert das aktuelle TextDocumentModel zum �bergebenen XTextDocument doc;
   * existiert zu doc noch kein TextDocumentModel, so wird hier eines erzeugt
   * und das neu erzeugte zur�ck geliefert.
   * 
   * @param doc
   *          Das XTextDocument, zu dem das zugeh�rige TextDocumentModel
   *          zur�ckgeliefert werden soll.
   * @return Das zu doc zugeh�rige TextDocumentModel.
   */
  public TextDocumentModel getTextDocumentModel(XTextDocument doc)
  {
    HashableComponent key = new HashableComponent(doc);

    TextDocumentModel model = (TextDocumentModel) currentTextDocumentModels
        .get(key);
    if (model == null)
    {
      // Neues TextDocumentModel erzeugen, wenn es noch nicht existiert.
      model = new TextDocumentModel(doc);
      currentTextDocumentModels.put(key, model);
    }
    return model;
  }

  /**
   * L�scht das �bergebene TextDocumentModel von doc aus der internen Liste
   * aller aktuellen TextDocumentModels.
   * 
   * @param doc
   *          Das XTextDocument, dessen zugeh�riges TextDocumentModel aus der
   *          internen Liste gel�scht werden soll.
   */
  public void disposedTextDocument(XTextDocument doc)
  {
    HashableComponent key = new HashableComponent(doc);
    currentTextDocumentModels.remove(key);
  }

  /**
   * Hilfsklasse, die es erm�glicht, UNO-Componenten in HashMaps abzulegen; der
   * Vergleich zweier HashableComponents mit equals(...) verwendet dazu den
   * sicheren UNO-Vergleich UnoRuntime.areSame(...) und die Methode hashCode
   * wird direkt an das UNO-Objekt weitergeleitet.
   * 
   * @author lut
   */
  public static class HashableComponent
  {
    private Object compo;

    public HashableComponent(XComponent compo)
    {
      this.compo = compo;
    }

    public int hashCode()
    {
      if (compo != null) return compo.hashCode();
      return 0;
    }

    public boolean equals(Object b)
    {
      if (b != null && b instanceof HashableComponent)
      {
        HashableComponent other = (HashableComponent) b;
        return UnoRuntime.areSame(this.compo, other.compo);
      }
      return false;
    }
  }

  /**
   * �berpr�ft, ob von url gelesen werden kann und wirft eine IOException, falls
   * nicht.
   * 
   * @throws IOException
   *           falls von url nicht gelesen werden kann.
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  public static void checkURL(URL url) throws IOException
  {
    url.openStream().close();
  }

  /**
   * Liefert eine vorgeparste UNO-URL von urlStr.
   * 
   * @param urlStr
   * @return vorgeparste UNO-URL von urlStr.
   */
  public static com.sun.star.util.URL getParsedUNOUrl(String urlStr)
  {
    com.sun.star.util.URL[] unoURL = new com.sun.star.util.URL[] { new com.sun.star.util.URL() };
    unoURL[0].Complete = urlStr;

    XURLTransformer trans = UNO.XURLTransformer(UNO
        .createUNOService("com.sun.star.util.URLTransformer"));
    if (trans != null) trans.parseStrict(unoURL);

    return unoURL[0];
  }

  /**
   * Holt sich den Frame von doc, f�hrt auf diesem ein queryDispatch() mit der
   * zu urlStr geh�renden URL aus und liefert den Ergebnis XDispatch zur�ck oder
   * null, falls der XDispatch nicht verf�gbar ist.
   * 
   * @param doc
   *          Das Dokument, dessen Frame f�r den Dispatch verwendet werden soll.
   * @param urlStr
   *          die URL in Form eines Strings (wird intern zu URL umgewandelt).
   * @return den gefundenen XDispatch oder null, wenn der XDispatch nicht
   *         verf�gbar ist.
   */
  public static XDispatch getDispatchForModel(XModel doc,
      com.sun.star.util.URL url)
  {
    if (doc == null) return null;

    XDispatchProvider dispProv = null;
    try
    {
      dispProv = UNO.XDispatchProvider(doc.getCurrentController().getFrame());
    }
    catch (java.lang.Exception e)
    {
    }

    if (dispProv != null)
    {
      return dispProv.queryDispatch(
          url,
          "_self",
          com.sun.star.frame.FrameSearchFlag.SELF);
    }
    return null;
  }

  /**
   * Der GlobalEventListener sorgt daf�r, dass der WollMux alle wichtigen
   * globalen Ereignisse wie z.B. ein OnNew on OnLoad abfangen und darauf
   * reagieren kann. In diesem Fall wird die Methode notifyEvent aufgerufen.
   * 
   * @author christoph.lutz
   */
  public static class GlobalEventListener implements
      com.sun.star.document.XEventListener
  {
    public void notifyEvent(com.sun.star.document.EventObject docEvent)
    {
      XTextDocument doc = UNO.XTextDocument(docEvent.Source);

      if (doc != null)
      {
        Logger.debug2("Incoming documentEvent for #"
                      + doc.hashCode()
                      + ": "
                      + docEvent.EventName);

        if (docEvent.EventName.equalsIgnoreCase("OnLoad")
            || docEvent.EventName.equalsIgnoreCase(("OnNew")))
        {
          // Verarbeitung von TextDocuments anstossen:
          WollMuxEventHandler.handleProcessTextDocument(doc);
        }
      }
    }

    public void disposing(EventObject arg0)
    {
      // nothing to do
    }
  }
}
