class StringIdMigration < ActiveRecord::Migration
  def self.up
    create_table "string_ids", :force => true, :id => false do |t|
      t.string :id, :null => false
    end
  end

  def self.down
    drop_table "string_ids"
  end
end
CreateStringIds = StringIdMigration

class StringId < ActiveRecord::Base
  def self.table_name; "string_ids"; end
  # Fake out a table without a primary key
  self.primary_key = "id"
end
