require 'asciidoctor-pdf'

class PDFConverterColumns < (Asciidoctor::Converter.for 'pdf')
  register_for 'pdf'

  def convert_open node
    if node.role? 'columns-2'
      column_box [bounds.left, cursor], columns: 2, width: bounds.width, reflow_margins: true do
        traverse node
      end
    else
      super
    end
  end

  def convert_page_break node
    if ColumnBox === bounds
      bounds.move_past_bottom
    else
      super
    end
  end
end
