/*
 * Dateiname: FormFieldFactory.java
 * Projekt  : WollMux
 * Funktion : Repr�sentiert eine Fabrik, die an der Stelle von 
 *            WM('insertFormValue'...)-Bookmark entsprechende FormField-Elemente
 *            erzeugt.
 * 
 * Copyright: Landeshauptstadt M�nchen
 *
 * �nderungshistorie:
 * Datum      | Wer | �nderungsgrund
 * -------------------------------------------------------------------
 * 08.06.2006 | LUT | Erstellung als FormField
 * 14.06.2006 | LUT | Umbenennung in FormFieldFactory und Unterst�tzung
 *                    von Checkboxen.
 * 07.09.2006 | BNK | Rewrite
 * -------------------------------------------------------------------
 *
 * @author Christoph Lutz (D-III-ITD 5.1)
 * @version 1.0
 * 
 */
package de.muenchen.allg.itd51.wollmux;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.star.awt.XControlModel;
import com.sun.star.container.XEnumeration;
import com.sun.star.container.XEnumerationAccess;
import com.sun.star.container.XNamed;
import com.sun.star.drawing.XControlShape;
import com.sun.star.frame.XController;
import com.sun.star.lang.XServiceInfo;
import com.sun.star.table.XCell;
import com.sun.star.text.XDependentTextField;
import com.sun.star.text.XText;
import com.sun.star.text.XTextCursor;
import com.sun.star.text.XTextDocument;
import com.sun.star.text.XTextField;
import com.sun.star.text.XTextRange;

import de.muenchen.allg.afid.UNO;
import de.muenchen.allg.itd51.wollmux.DocumentCommand.InsertFormValue;
import de.muenchen.allg.itd51.wollmux.func.FunctionLibrary;

/**
 * Repr�sentiert eine Fabrik, die an der Stelle von
 * WM('insertFormValue'...)-Bookmark entsprechende FormField-Elemente erzeugt.
 * 
 * @author lut
 */
public final class FormFieldFactory
{
  public static final Pattern INSERTFORMVALUE = Pattern.compile("\\A\\s*(WM\\s*\\(.*CMD\\s*'((insertFormValue))'.*\\))\\s*\\d*\\z");
  
  /**
   * Erzeugt ein Formualfeld im Dokument doc an der Stelle des
   * InsertFormValue-Kommandos cmd. Ist unter dem bookmark bereits ein
   * Formularelement (derzeit TextFeld vom Typ Input, DropDown oder eine
   * Checkbox) vorhanden, so wird dieses Feld als Formularelement f�r die
   * Darstellung des Wertes des Formularfeldes genutzt. Ist innerhalb des
   * Bookmarks noch kein Formularelement vorhanden, so wird ein neues InputField
   * in den Bookmark eingef�gt.
   * 
   * @param doc
   *          das Dokument, zu dem das Formularfeld geh�rt.
   * @param cmd
   *          das zugeh�rige insertFormValue-Kommando.
   */
  public static FormField createFormField(XTextDocument doc, InsertFormValue cmd, Map bookmarkNameToFormField)
  {
    String bookmarkName = cmd.getBookmarkName();
    FormField formField = (FormField)bookmarkNameToFormField.get(bookmarkName);
    if (formField != null) return formField;
    
    /*
     * Falls die range in einer Tabellenzelle liegt, wird sie auf die ganze Zelle
     * ausgeweitet, damit die ganze Zelle gescannt wird (Workaround f�r Bug
     * http://qa.openoffice.org/issues/show_bug.cgi?id=68261)
     */
    XTextRange range = cmd.getAnchor();
    if (range != null)
    {
      range = range.getText();
      XCell cell = UNO.XCell(range);
      if (cell == null) 
        range = null;
      else
        if (WollMuxFiles.isDebugMode())
        {
          String cellName = (String)UNO.getProperty(cell, "CellName");
          Logger.debug("Scanne Zelle "+cellName);
        }
    }
    
    if (range == null)
      range = cmd.getTextRange();
    
    if (range != null)
    {
      XEnumeration xenum = UNO.XEnumerationAccess(range).createEnumeration();
      handleParagraphEnumeration(xenum, doc, bookmarkNameToFormField);
    }

    return (FormField)bookmarkNameToFormField.get(bookmarkName);
  }
  
  /**
   * Geht die XEnumeration enu von Abs�tzen und TextTables durch und erzeugt f�r alle
   * in Abs�tzen (nicht TextTables) enthaltenen insertFormValue-Bookmarks entsprechende Eintr�ge in
   * mapBookmarkNameToFormField. Falls n�tig wird das entsprechende FormField erzeugt. 
   * @param doc das Dokument in dem sich die enumierten Objekte befinden.
   * @author Matthias Benkmann (D-III-ITD 5.1)
   */
  private static void handleParagraphEnumeration(XEnumeration enu, XTextDocument doc, Map mapBookmarkNameToFormField)
  {
    XEnumerationAccess enuAccess;
    while (enu.hasMoreElements())
    {
      Object ele;
      try{ ele = enu.nextElement(); } catch(java.lang.Exception x){continue;}
      enuAccess = UNO.XEnumerationAccess(ele);
      if (enuAccess != null) //ist wohl ein SwXParagraph
      {
        handleParagraph(enuAccess, doc, mapBookmarkNameToFormField);
      }
    }
  }
  
  /**
  * Geht die XEnumeration enuAccess von TextPortions durch und erzeugt f�r alle
  * enthaltenen insertFormValue-Bookmarks entsprechende Eintr�ge in
  * mapBookmarkNameToFormField. Falls n�tig wird das entsprechende FormField erzeugt. 
  * @param doc das Dokument in dem sich die enumierten Objekte befinden.
  * @author Matthias Benkmann (D-III-ITD 5.1)
  */
  private static void handleParagraph(XEnumerationAccess paragraph, XTextDocument doc, Map mapBookmarkNameToFormField)
  {
    /*
     * Der Name des zuletzt gestarteten insertFormValue-Bookmarks.
     */
    String lastInsertFormValueStart = null;
    
    /*
     * enumeriere alle TextPortions des Paragraphs
     */
    XEnumeration textPortionEnu = paragraph.createEnumeration();
    while (textPortionEnu.hasMoreElements())
    {
      /*
       * Diese etwas seltsame Konstruktion beugt Exceptions durch Bugs wie 68261 vor.
       */
      Object textPortion;
      try{ textPortion = textPortionEnu.nextElement(); } catch(java.lang.Exception x){continue;};
      
      String textPortionType = (String)UNO.getProperty(textPortion, "TextPortionType");
      if (textPortionType.equals("Bookmark"))
      {
        boolean isStart = false;
        XNamed bookmark = null; 
        try{
          isStart = ((Boolean)UNO.getProperty(textPortion, "IsStart")).booleanValue();
          bookmark = UNO.XNamed(UNO.getProperty(textPortion, "Bookmark"));
        } catch(java.lang.Exception x){ continue;}
        if (bookmark == null) continue;
        
        String name = bookmark.getName();
        Matcher m = INSERTFORMVALUE.matcher(name);
        if (m.matches())
        {
          if (isStart)
          {
            lastInsertFormValueStart = name;
          }
          else
          {
            if (name.equals(lastInsertFormValueStart))
            {
              handleNewInputField(lastInsertFormValueStart, bookmark, mapBookmarkNameToFormField, doc);
              lastInsertFormValueStart = null;
            }
          }
        }
      } else if (textPortionType.equals("TextField"))
      {
        XDependentTextField textField = null;
        int textfieldType = 0; //0:input, 1:dropdown, 2: reference
        try{
          textField = UNO.XDependentTextField(UNO.getProperty(textPortion,"TextField"));
          XServiceInfo info = UNO.XServiceInfo(textField); 
          if (info.supportsService("com.sun.star.text.TextField.DropDown"))
            textfieldType = 1;
          else if (info.supportsService("com.sun.star.text.TextField.Input"))
            textfieldType = 0;
          else
            continue; //sonstiges TextField 
        } catch(java.lang.Exception x){ continue;}
        
        switch(textfieldType)
        {
          case 0: handleInputField(textField, lastInsertFormValueStart, mapBookmarkNameToFormField, doc); break;
          case 1: handleDropdown(textField, lastInsertFormValueStart, mapBookmarkNameToFormField, doc); break;
        }
        lastInsertFormValueStart = null;
      } else if (textPortionType.equals("Frame"))
      {
        XControlModel model = null;
        try{
          XEnumeration contentEnum = UNO.XContentEnumerationAccess(textPortion).createContentEnumeration("com.sun.star.text.TextPortion");
          while (contentEnum.hasMoreElements())
          {
            XControlShape tempShape = null;
            try{tempShape = UNO.XControlShape(contentEnum.nextElement());}catch(java.lang.Exception x){}
            XControlModel tempModel = tempShape.getControl();
            XServiceInfo info = UNO.XServiceInfo(tempModel); 
            if (info.supportsService("com.sun.star.form.component.CheckBox"))
            {
              model = tempModel;
            }
          }
        } catch(java.lang.Exception x){ continue;}

        handleCheckbox(model, lastInsertFormValueStart, mapBookmarkNameToFormField, doc);
        lastInsertFormValueStart = null;
      }
      else //sonstige TextPortion
        continue;
    }

  }
  
  /**
   * F�gt ein neues Eingabefeld innerhalb des Bookmarks bookmark ein, erzeugt ein
   * dazugeh�riges FormField und setzt ein passendes Mapping von
   * bookmarkName auf dieses FormField in mapBookmarkNameToFormField
   * 
   * @author Matthias Benkmann (D-III-ITD 5.1)
   * TESTED
   */
  private static void handleNewInputField(String bookmarkName, XNamed bookmark, Map mapBookmarkNameToFormField, XTextDocument doc)
  {
    XTextField field = null;
    
    Logger.debug2("Erzeuge neues Input-Field f�r Bookmark \""+bookmarkName+"\"");
    try
    {
      XTextRange range = UNO.XTextContent(bookmark).getAnchor();
      XText text = range.getText();
      field = UNO.XTextField(UNO.XMultiServiceFactory(doc).createInstance(
      "com.sun.star.text.TextField.Input"));
      XTextCursor cursor = text.createTextCursorByRange(range);
      
      if (cursor != null && field != null)
      {
        text.insertTextContent(cursor, field, true);
// Dieser Code scheint nicht n�tig zu sein. Ich hatte zuerst gedacht, ich m�sste
// das Bookmark regenerieren, damit es das neue Feld korrekt umschliesst, aber es funzt
// offenbar auch ohne.
//        text.removeTextContent(UNO.XTextContent(bookmark));
//        cursor = text.createTextCursorByRange(field.getAnchor());
//        bookmark = UNO.XNamed(UNO.XMultiServiceFactory(doc).createInstance(
//        "com.sun.star.text.Bookmark"));
//        bookmark.setName(bookmarkName);
//        text.insertTextContent(cursor, UNO.XTextContent(bookmark), true);
      }
    }
    catch (java.lang.Exception e)
    {
      Logger.error(e);
    }
  
    if (field != null) 
    {
      FormField formField = new InputFormField(doc, null, field);
      mapBookmarkNameToFormField.put(bookmarkName, formField);
    }
  }
  
  private static void handleInputField(XDependentTextField textfield, String bookmarkName, Map mapBookmarkNameToFormField, XTextDocument doc)
  {
    if (textfield != null) 
    {
      FormField formField = new InputFormField(doc, null, textfield);
      mapBookmarkNameToFormField.put(bookmarkName, formField);
    }
  }
  
  private static void handleDropdown(XDependentTextField textfield, String bookmarkName, Map mapBookmarkNameToFormField, XTextDocument doc)
  {
    if (textfield != null) 
    {
      FormField formField = new DropDownFormField(doc, null, textfield);
      mapBookmarkNameToFormField.put(bookmarkName, formField);
    }
  }
  
  private static void handleCheckbox(XControlModel checkbox, String bookmarkName, Map mapBookmarkNameToFormField, XTextDocument doc)
  {
    if (checkbox != null) 
    {
      FormField formField = new CheckboxFormField(doc, null, checkbox);
      mapBookmarkNameToFormField.put(bookmarkName, formField);
    }
  }
  
  /**
   * Dieses Interface beschreibt die Eigenschaften eines Formularfeldes unter
   * einem WM(CMD'insertFormValue'...)-Kommando.
   * 
   * @author Christoph Lutz (D-III-ITD 5.1)
   */
  interface FormField
  {
    /**
     * FIXME Unsch�ne Fixup-Funktion, die in FormScanner.executeCommand() aufgerufen
     * wird, da der Scan von Tabellenzellen nur die Bookmarks, aber nicht die
     * zugeh�rigen Commands kennt. 
     * @author Matthias Benkmann (D-III-ITD 5.1)
     */
    public abstract void setCommand(InsertFormValue cmd);
    
    /**
     * Gibt an, ob der Inhalt des Formularelements mit der zuletzt gesetzten
     * MD5-Sum des Feldes �bereinstimmt oder ob der Wert seitdem ge�ndert wurde
     * (z.B. durch manuelles Setzen des Feldinhalts durch den Benutzer). Ist
     * noch kein MD5-Vergleichswert vorhanden, so wird false zur�ckgeliefert.
     * 
     * @return true, wenn der Inhalt des Formularelements nicht mehr mit der
     *         zuletzt gesetzten MD5-Sum �bereinstimmt, ansonsten false
     */
    public abstract boolean hasChangedPreviously();

    /**
     * Die Methode liefert true, wenn das insertFormValue-Kommando eine
     * TRAFO-Funktion verwendet, um den gesetzten Wert zu transformieren,
     * ansonsten wird false zur�ckgegeben.
     */
    public abstract boolean hasTrafo();

    /**
     * Diese Methode belegt den Wert des Formularfeldes im Dokument mit dem
     * neuen Inhalt value; ist das Formularfeld mit einer TRAFO belegt, so wird
     * vor dem setzen des neuen Inhaltes die TRAFO-Funktion ausgef�hrt und der
     * neu berechnete Wert stattdessen eingef�gt.
     * 
     * @param value
     */
    public abstract void setValue(String value, FunctionLibrary funcLib);

    /**
     * Diese Methode liefert den aktuellen Wert des Formularfeldes als String
     * zur�ck oder null, falls der Wert nicht bestimmt werden kann.
     * 
     * @return der aktuelle Wert des Formularfeldes als String
     */
    public abstract String getValue();

    /**
     * Liefert true, wenn das Dokumentkommando eine Checksumme (MD5) im Status
     * enth�lt, �ber die festgestellt werden kann, ob der Wert des Eingabefeldes
     * noch mit der zuletzt gesetzten Checksumme �bereinstimmt (siehe
     * hasChangedPreviously())
     */
    public abstract boolean hasChecksum();

    /**
     * Setzt den ViewCursor auf die Position des InputFields.
     * 
     * @param doc
     */
    public abstract void focus();
  }

  private static abstract class BasicFormField implements FormField
  {
    private XTextDocument doc;

    private InsertFormValue cmd;
    
    public void setCommand(InsertFormValue cmd) {this.cmd = cmd;};

    /**
     * Erzeugt ein Formualfeld im Dokument doc an der Stelle des
     * InsertFormValue-Kommandos cmd. Ist unter dem bookmark bereits ein
     * TextFeld vom Typ InputField vorhanden, so wird dieses Feld als inputField
     * f�r die Darstellung des Wertes des Formularfeldes genutzt. Ist innerhalb
     * des Bookmarks noch kein InputField vorhanden, so wird ein neues
     * InputField in den Bookmark eingef�gt.
     * 
     * @param doc
     *          das Dokument, zu dem das Formularfeld geh�rt.
     * @param cmd
     *          das zugeh�rige insertFormValue-Kommando.
     * @param focusRange
     *          Beschreibt die range, auf die der ViewCursor beim Aufruf der
     *          focus()-methode gesetzt werden soll. Der Parameter ist
     *          erforderlich, da das Setzen des viewCursors auf die TextRanges
     *          des Kommandos cmd unter Linux nicht sauber funktioniert.
     */
    public BasicFormField(XTextDocument doc, InsertFormValue cmd)
    {
      this.doc = doc;
      this.cmd = cmd;
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.muenchen.allg.itd51.wollmux.FormField#hasChangedPreviously()
     */
    public boolean hasChangedPreviously()
    {
      String value = getValue();
      String lastSetMD5 = cmd.getMD5();
      if (value != null && lastSetMD5 != null)
      {
        String md5 = getMD5HexRepresentation(value);
        return !(md5.equals(lastSetMD5));
      }
      return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.muenchen.allg.itd51.wollmux.FormField#hasTrafo()
     */
    public boolean hasTrafo()
    {
      return (cmd.getTrafoName() != null);
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.muenchen.allg.itd51.wollmux.FormField#setValue(java.lang.String,
     *      de.muenchen.allg.itd51.wollmux.func.FunctionLibrary)
     */
    public void setValue(String value, FunctionLibrary funcLib)
    {
      // ggf. TRAFO durchf�hren
      value = DocumentCommandInterpreter.getOptionalTrafoValue(
          value,
          cmd,
          funcLib);

      // md5-Wert bestimmen und setzen:
      String md5 = getMD5HexRepresentation(value);
      cmd.setMD5(md5);
      cmd.updateBookmark(false);

      // Inhalt des Textfeldes setzen:
      setFormElementValue(value);

    }

    /**
     * Diese Methode setzt den Inhalt des internen Formularelements auf den
     * neuen Wert value.
     * 
     * @param value
     *          der neue Wert des Formularelements.
     */
    public abstract void setFormElementValue(String value);

    /**
     * Diese Methode liest den Inhalt des internen Formularelements und liefert
     * den Wert als String zur�ck.
     * 
     * @param value
     *          der neue Wert des Formularelements.
     */
    public abstract String getFormElementValue();

    /*
     * (non-Javadoc)
     * 
     * @see de.muenchen.allg.itd51.wollmux.FormField#getValue()
     */
    public String getValue()
    {
      return getFormElementValue();
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.muenchen.allg.itd51.wollmux.FormField#hasChecksum()
     */
    public boolean hasChecksum()
    {
      return (cmd.getMD5() != null);
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.muenchen.allg.itd51.wollmux.FormField#focus()
     */
    public void focus()
    {
      try
      {
        XController controller = UNO.XModel(doc).getCurrentController();
        XTextCursor cursor = UNO.XTextViewCursorSupplier(controller)
            .getViewCursor();
        XTextRange focusRange = cmd.getTextRange();
        if (focusRange != null) cursor.gotoRange(focusRange, false);
      }
      catch (java.lang.Exception e)
      {
      }
    }

    // Helper-Methoden:

    /**
     * Liefert den HEX-Wert des MD5-Hashes von string als String-Repr�sentation.
     * 
     * @param string
     * @return Liefert den HEX-Wert des MD5-Hashes von string als
     *         String-Repr�sentation.
     */
    private String getMD5HexRepresentation(String string)
    {
      String md5 = "";
      try
      {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] md5bytes = md.digest(string.getBytes("UTF-8"));
        for (int k = 0; k < md5bytes.length; k++)
        {
          byte b = md5bytes[k];
          String str = Integer.toHexString(b + 512);
          md5 += str.substring(1);
        }
      }
      catch (NoSuchAlgorithmException e)
      {
        Logger.error(e);
      }
      catch (UnsupportedEncodingException e)
      {
        Logger.error(e);
      }
      return md5;
    }
  }

  /**
   * Repr�sentiert ein FormField, das den Formularwert in einem Input-Field
   * darstellt.
   * 
   * @author Christoph Lutz (D-III-ITD 5.1)
   */
  private static class InputFormField extends BasicFormField
  {
    private XTextField inputField;

    public InputFormField(XTextDocument doc, InsertFormValue cmd,
        XTextField inputField)
    {
      super(doc, cmd);
      this.inputField = inputField;
    }

    public void setFormElementValue(String value)
    {
      if (inputField != null && UNO.XUpdatable(inputField) != null)
      {
        UNO.setProperty(inputField, "Content", value);
        UNO.XUpdatable(inputField).update();
      }
    }

    public String getFormElementValue()
    {
      if (inputField != null)
      {
        Object content = UNO.getProperty(inputField, "Content");
        if (content != null) return content.toString();
      }
      return "";
    }
  }

  /**
   * Repr�sentiert ein FormField, das den Formularwert in einem DropDown-Field
   * darstellt.
   * 
   * @author Christoph Lutz (D-III-ITD 5.1)
   */
  private static class DropDownFormField extends BasicFormField
  {
    private XTextField dropdownField;

    private String[] origItemList = null;

    public DropDownFormField(XTextDocument doc, InsertFormValue cmd,
        XTextField dropdownField)
    {
      super(doc, cmd);
      this.dropdownField = dropdownField;

      if (dropdownField != null)
        origItemList = (String[]) UNO.getProperty(dropdownField, "Items");

    }

    public void setFormElementValue(String value)
    {
      if (dropdownField != null && UNO.XUpdatable(dropdownField) != null)
      {
        extendItemsList(value);
        UNO.setProperty(dropdownField, "SelectedItem", value);
        UNO.XUpdatable(dropdownField).update();
      }
    }

    /**
     * Die Methode pr�ft, ob der String value bereits in der zum Zeitpunkt des
     * Konstruktoraufrufs eingelesenen Liste oritItemList der erlaubten Eintr�ge
     * der ComboBox vorhanden ist und erweitert die Liste um value, falls nicht.
     * 
     * @param value
     *          der Wert, der ggf. an in die Liste der erlaubten Eintr�ge
     *          aufgenommen wird.
     */
    private void extendItemsList(String value)
    {
      if (origItemList != null)
      {
        boolean found = false;
        for (int i = 0; i < origItemList.length; i++)
        {
          if (value.equals(origItemList[i]))
          {
            found = true;
            break;
          }
        }

        if (!found)
        {
          String[] extendedItems = new String[origItemList.length + 1];
          for (int i = 0; i < origItemList.length; i++)
            extendedItems[i] = origItemList[i];
          extendedItems[origItemList.length] = value;
          UNO.setProperty(dropdownField, "Items", extendedItems);
        }
      }
    }

    public String getFormElementValue()
    {
      if (dropdownField != null)
      {
        Object content = UNO.getProperty(dropdownField, "SelectedItem");
        if (content != null) return content.toString();
      }
      return "";
    }
  }

  /**
   * Repr�sentiert ein FormField, das den Formularwert in einer Checkbox
   * darstellt.
   * 
   * @author Christoph Lutz (D-III-ITD 5.1)
   */
  private static class CheckboxFormField extends BasicFormField
  {
    private Object checkbox;

    /**
     * Erzeugt eine neue CheckboxFormField, das eine bereits im Dokument doc
     * bestehende Checkbox checkbox vom Service-Typ
     * com.sun.star.form.component.CheckBox an der Stelle des Kommandos cmd
     * repr�sentiert.
     * 
     * @param doc
     *          Das Dokument in dem sich das Checkbox-Formularfeld-Kommando
     *          befindet
     * @param cmd
     *          das zum Formularfeld zugeh�rige insertFormValue-Kommando
     * @param checkbox
     *          Ein UNO-Service vom Typ von com.sun.star.form.component.CheckBox
     *          das den Zugriff auf das entsprechende FormControl-Element
     *          erm�glicht.
     * @param focusRange
     *          Beschreibt die range, auf die der ViewCursor beim Aufruf der
     *          focus()-methode gesetzt werden soll.
     */
    public CheckboxFormField(XTextDocument doc, InsertFormValue cmd,
        Object checkbox)
    {
      super(doc, cmd);

      this.checkbox = checkbox;
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.muenchen.allg.itd51.wollmux.FormFieldFactory.BasicFormField#setFormElementValue(java.lang.String)
     */
    public void setFormElementValue(String value)
    {
      Boolean bv = new Boolean(value);

      UNO.setProperty(checkbox, "State", ((bv.booleanValue()) ? new Short(
          (short) 1) : new Short((short) 0)));
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.muenchen.allg.itd51.wollmux.FormFieldFactory.BasicFormField#getFormElementValue()
     */
    public String getFormElementValue()
    {
      Object state = UNO.getProperty(checkbox, "State");
      if (state != null && state.equals(new Short((short) 1)))
        return "true";
      else
        return "false";
    }
  }
}
