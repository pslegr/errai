package org.jboss.errai.ui.test.basic.client.res;

import javax.annotation.PostConstruct;
import javax.enterprise.context.Dependent;

import org.jboss.errai.ui.shared.api.annotations.Insert;
import org.jboss.errai.ui.shared.api.annotations.Replace;
import org.jboss.errai.ui.shared.api.annotations.Templated;

import com.google.gwt.user.client.ui.Anchor;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;

@Dependent
@Templated
public class BasicComponent extends Composite {

  @Replace("c1")
  private Label content;

  @Insert
  private Button c2;

  @Replace
  private TextBox c3;

  @Replace
  private Anchor c4;
  
  @Replace
  private Image c6;

  @Replace
  private Anchor c5;
  
  @PostConstruct
  public void init()
  {
    content.getElement().setAttribute("id","c1");
    content.setText("Added by component");
  }

  public Label getLabel() {
    return content;
  }
  
  public Button getContent2() {
    return c2;
  }
  
  public TextBox getTextBox() {
    return c3;
  }
  
  public void setTextBox(TextBox box)
  {
    this.c3 = box;
  }
  
  public Anchor getC4() {
    return c4;
  }
  
  public Anchor getC5() {
    return c5;
  }
  
  public Image getC6() {
    return c6;
  }
}
