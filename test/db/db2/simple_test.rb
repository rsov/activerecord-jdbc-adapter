require File.expand_path('test_helper', File.dirname(__FILE__))

class DB2SimpleTest < Test::Unit::TestCase
  include SimpleTestMethods
  include DirtyAttributeTests
  include XmlColumnTestMethods

  def xml_sql_type; 'XML'; end

  # @override
  def test_time_usec_formatting_when_saved_into_string_column
    e = DbType.create!(:sample_string => '')
    t = Time.now
    value = Time.local(t.year, t.month, t.day, t.hour, t.min, t.sec, 0)

    e.sample_string = value
    e.save!; e.reload

    if ActiveRecord::VERSION::STRING > '4.2'
      assert_equal value.to_s(:db), e.sample_string[0...19]
      return
    elsif ActiveRecord::VERSION::MAJOR >= 3
      # AR-3 adapters override quoted_date which is called always when a
      # Time like value is passed (... as well for string/text columns) :
      str = value.utc.to_s(:db) << '.' << sprintf("%06d", value.usec)
    else # AR-2.x #quoted_date did not do TZ conversions
      str = value.to_s(:db)
    end
    assert_equal str, e.sample_string
  end

  # For backwards compatibility with how the DB2 code in
  # jdbc_adapter 0.9.x handled booleans.
  #
  # The old DB2 jdbc_db2.rb driver was broken enough that
  # applications were exposed to the underlying type (was DECIMAL)
  # and used 0 and 1 as false and true, respectively.
  #
  # This driver now uses SMALLINT as a boolean, and properly
  # type cast's it to a Ruby boolean. Need to make sure we don't
  # break existing apps!
  def test_boolean_as_integer
    e = DbType.create! :sample_boolean => nil

    # true
    e.sample_boolean = 1
    assert_equal true, e.sample_boolean
    assert_equal true, e.sample_boolean?
    e.save!

    e.reload
    assert_equal true, e.sample_boolean
    assert_equal true, e.sample_boolean?

    # false
    e.sample_boolean = 0
    assert_equal false, e.sample_boolean
    assert_equal false, e.sample_boolean?
    e.save!

    e.reload
    assert_equal false, e.sample_boolean
    assert_equal false, e.sample_boolean?
  end

  def test_emulates_booleans_by_default
    assert_true ArJdbc::DB2.emulate_booleans?
  end if ar_version('3.0')

  def test_boolean_emulation_can_be_disabled
    db_type = DbType.create! :sample_boolean => true
    column = DbType.columns.find { |col| col.name.to_s == 'sample_boolean' }
    assert_equal :boolean, column.type
    ArJdbc::DB2.emulate_booleans = false

    DbType.reset_column_information
    column = DbType.columns.find { |col| col.name.to_s == 'sample_boolean' }
    assert_equal :integer, column.type

    assert_equal 1, db_type.reload.sample_boolean
  ensure
    ArJdbc::DB2.emulate_booleans = true
    DbType.reset_column_information
  end if ar_version('3.0')

  def test_find_by_sql_WITH_statement
    user = User.create! :login => 'ferko'
    Entry.create! :title => 'aaa', :user_id => user.id
    entries = Entry.find_by_sql '' +
      'WITH EntryAndUser (title, login, updated_on) AS ' +
      '(' +
      ' SELECT e.title, u.login, e.updated_on ' +
      ' FROM entries e INNER JOIN users u ON e.user_id = u.id ' +
      ')' +
      ' ' +
      'SELECT * FROM EntryAndUser ORDER BY title ASC'
    assert entries.first
    assert entries.first.title
    assert entries.first.login
  end

  test 'returns correct visitor type' do
    assert_not_nil visitor = connection.instance_variable_get(:@visitor)
    assert defined? Arel::Visitors::DB2
    assert_kind_of Arel::Visitors::DB2, visitor
  end if ar_version('3.0')

  test 'identity_val_local()' do
    e = Entry.create! :title => '1'
    assert_equal e.id, connection.last_insert_id

    e = Entry.create! :title => '2'
    e = Entry.create! :title => '3'
    assert_equal e.id, connection.last_insert_id
    #assert_equal e.id, connection.last_insert_id('entries')

    db = DbType.create! :sample_float => 0.1
    assert_equal db.id, connection.last_insert_id
    #assert_equal e.id, connection.last_insert_id('entries')
  end

  # DB2 does not like "= NULL".
  def test_equals_null
    Entry.create!(:title => "Foo")
    if ar_version('4.0')
      entry = Entry.where("content = NULL").first
    else
      entry = Entry.find(:first, :conditions => ["content = NULL"])
    end
    assert_equal "Foo", entry.title
  end

  # DB2 does not like "!= NULL" or "<> NULL".
  def test_not_equals_null
    Entry.create!(:title => "Foo", :content => "Bar")
    if ar_version('4.0')
      entry = Entry.where(:title => 'Foo').where('content != NULL').first
    else
      entry = Entry.find_by_title("Foo", :conditions => ["content != NULL"])
    end
    assert_equal "Foo", entry.title
    if ar_version('4.0')
      entry = Entry.where("title = 'Foo' AND content <> null").first
    else
      entry = Entry.find_by_title("Foo", :conditions => ["content <> NULL"])
    end
    assert_equal "Foo", entry.title
  end

end

class DB2LimitOffsetTest < Test::Unit::TestCase

  class CreateTablesForAddLimitOffsetTestMigration < ActiveRecord::Migration
    def up
      create_table "names" do |t|
        t.string   :name
        t.integer  :person_id
      end

      create_table "persons" do |t|
        t.string   :tax_code
      end
    end

    def down
      %w{names persons}.each do |t|
        drop_table t
      end
    end
  end

  setup { CreateTablesForAddLimitOffsetTestMigration.migrate :up }
  teardown { CreateTablesForAddLimitOffsetTestMigration.migrate :down }

  class Name < ActiveRecord::Base; end
  class Person < ActiveRecord::Base; self.table_name = 'persons' end

  test "should handle pagination with ordering" do
    assert_empty arel_with_pagination(3).all

    person = Person.create! :tax_code => '1234567890'
    Name.create! :name => 'benissimo', :person_id => person.id

    assert_empty arel_with_pagination(2).all

    assert_not_empty arel_with_pagination(0).all
  end

  test "should handle pagination with ordering even when order column is not returned" do
    # passes on 1.2.9, failed on <= 1.3.13
    assert_empty arel_with_pagination(3).order("p.tax_code").all
  end

  private

  def arel_with_pagination(offset = 0)
    Name.joins("JOIN persons p on p.id = names.person_id").limit(2).offset(offset)
  end

end
