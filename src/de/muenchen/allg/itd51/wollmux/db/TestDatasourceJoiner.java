/*
* Dateiname: TestDatasourceJoiner.java
* Projekt  : WollMux
* Funktion : Variante des DatasourceJoiners, die zum testen besser geeignet ist.
* 
* Copyright: Landeshauptstadt München
*
* Änderungshistorie:
* Datum      | Wer | Änderungsgrund
* -------------------------------------------------------------------
* 19.10.2005 | BNK | Erstellung
* 20.10.2005 | BNK | Fertig
* 20.10.2005 | BNK | Fallback Rolle -> OrgaKurz
* 24.10.2005 | BNK | Erweitert um die Features, die PAL Verwalten braucht
* 31.10.2005 | BNK | TestDJ ist jetzt nur noch normaler DJ mit Default-
*                    initialisierung und ohne speichern
* 03.11.2005 | BNK | besser kommentiert
* -------------------------------------------------------------------
*
* @author Matthias Benkmann (D-III-ITD 5.1)
* @version 1.0
* 
*/
package de.muenchen.allg.itd51.wollmux.db;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Iterator;
import java.util.Set;

import de.muenchen.allg.itd51.parser.ConfigThingy;
import de.muenchen.allg.itd51.wollmux.TimeoutException;


/**
 * Variante des DatasourceJoiners, die zum testen besser geeignet ist.
 * @author Matthias Benkmann (D-III-ITD 5.1)
 */
public class TestDatasourceJoiner extends DatasourceJoiner
{
  /** TestDJ soll nichts (ungewollt) überschreiben, deshalb hier no-op (aber
   * es gibt reallySaveCacheAndLOS()).
   */ 
  public void saveCacheAndLOS(File cacheFile)
  {
    //TestDJ soll nichts (ungewollt) überschreiben
  }
  
   /**
    * Speichert den LOS und den Cache in der Datei cacheFile.  
    * @throws IOException falls ein Fehler beim Speichern auftritt.
    * @author Matthias Benkmann (D-III-ITD 5.1)
    */
  public void reallySaveCacheAndLOS(File cacheFile) throws IOException
  {
    super.saveCacheAndLOS(cacheFile);
  }
  
  /**
   * Erzeugt einen TestDatasourceJoiner (im wesentlichen ein DatasourceJoiner,
   * der mit bestimmten hartcodierten Vorgaben initialisiert wird). 
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  public TestDatasourceJoiner()
  { //TESTED
    try
    {
      File curDir = new File(System.getProperty("user.dir"));
      URL context = curDir.toURL();
      File losCache = new File(curDir, "testdata/cache.conf");
      String confFile = "testdata/testdjjoin.conf";
      URL confURL = new URL(context,confFile);
      ConfigThingy joinConf = new ConfigThingy("",confURL);
      init(joinConf, "Personal", losCache, context);
    }
    catch (Exception e)
    {
      e.printStackTrace();
      System.exit(1);
    }
  }
  
  
  /**
   * Gibt results aus. 
   * @param query ein String der in die Überschrift der Ausgabe geschrieben wird,
   * damit der Benutzer sieht, was er angezeigt bekommt.
   * @param schema bestimmt, welche Spalten angezeigt werden von den
   * Datensätzen aus results.
   * @param results die Ergebnisse der Anfrage.
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  public static void printResults(String query, Set schema, QueryResults results)
  {
    System.out.println("Results for query \""+query+"\":");
    Iterator resIter = results.iterator();
    while (resIter.hasNext())
    {
      Dataset result = (Dataset)resIter.next();
      
      Iterator spiter = schema.iterator();
      while (spiter.hasNext())
      {
        String spalte = (String)spiter.next();
        String wert = "Spalte "+spalte+" nicht gefunden!";
        try{ 
          wert = result.get(spalte);
          if (wert == null) 
            wert = "unbelegt";
          else
            wert = "\""+wert+"\"";
        }catch(ColumnNotFoundException x){};
        System.out.print(spalte+"="+wert+(spiter.hasNext()?", ":""));
      }
      System.out.println();
    }
    System.out.println();
  }
  
  /**
   * Es kann als Argument ein Datei-Pfad übergeben werden, unter dem dann
   * der LOS und Cache gespeichert wird.
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  public static void main(String[] args) throws TimeoutException, IOException
  {
    TestDatasourceJoiner dj = new TestDatasourceJoiner();
    printResults("Nachname = Benkmux", dj.getMainDatasourceSchema(), dj.find("Nachname","Benkmux"));
    printResults("Nachname = Benkm*", dj.getMainDatasourceSchema(), dj.find("Nachname","Benkm*"));
    printResults("Nachname = *ux", dj.getMainDatasourceSchema(), dj.find("Nachname","*ux"));
    printResults("Nachname = *oe*", dj.getMainDatasourceSchema(), dj.find("Nachname","*oe*"));
    printResults("Nachname = Schlonz", dj.getMainDatasourceSchema(), dj.find("Nachname","Schlonz"));
    printResults("Nachname = Lutz", dj.getMainDatasourceSchema(), dj.find("Nachname","Lutz"));
    printResults("Nachname = *uX, Vorname = m*", dj.getMainDatasourceSchema(), dj.find("Nachname","*uX","Vorname","m*"));
    printResults("Homepage = *limux", dj.getMainDatasourceSchema(), dj.find("Homepage","*limux"));
    printResults("Homepage = *limux, Nachname = B*", dj.getMainDatasourceSchema(), dj.find("Homepage","*limux","Nachname","B*"));
    printResults("Local Override Storage", dj.getMainDatasourceSchema(), dj.getLOS());
    
    if (args.length == 1)
    {
      File outFile = new File(args[0]);
      dj.reallySaveCacheAndLOS(outFile);
    }
  }
  
}
