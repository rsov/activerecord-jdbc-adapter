# encoding: utf-8
require 'db/sqlite3'
require 'models/db_type'

class SQLite3TypeConversionTest < Test::Unit::TestCase

  if defined?(JRUBY_VERSION)
    MAX_INTEGER_VALUE =  Java::JavaLang::Integer::MAX_VALUE
  else
    MAX_INTEGER_VALUE = 2147483647
  end

  def self.startup; super; DbTypeMigration.up; end
  def self.shutdown; DbTypeMigration.down; super; end

  TEST_BINARY = "Some random binary data % \0 and then some"

  @@time_zone = Time.zone

  setup do
    Time.zone = ActiveSupport::TimeZone['UTC']
    DbType.delete_all
    some_time = Time.now
    DbType.create!(
      :sample_timestamp => some_time,
      :sample_datetime => some_time.to_datetime,
      :sample_time => some_time.to_time,
      :sample_date => some_time.to_date,
      :sample_decimal => MAX_INTEGER_VALUE + 1,
      :sample_small_decimal => 3.14,
      :sample_binary => TEST_BINARY)
    DbType.create!(
      :sample_timestamp => some_time,
      :sample_datetime => some_time.to_datetime,
      :sample_time => some_time.to_time,
      :sample_date => some_time.to_date,
      :sample_decimal => MAX_INTEGER_VALUE + 1,
      :sample_small_decimal => 1.0,
      :sample_binary => TEST_BINARY)
  end

  teardown { Time.zone = @@time_zone; DbType.delete_all }

  def test_decimal
    types = DbType.first
    assert_equal MAX_INTEGER_VALUE + 1, types.sample_decimal
  end

  def test_decimal_scale
    assert_equal(2, DbType.columns_hash["sample_small_decimal"].scale)
  end

  def test_decimal_precision
    assert_equal(3, DbType.columns_hash["sample_small_decimal"].precision)
  end

  def test_small_decimal_with_ordering
    if ar_version('4.0')
      types = DbType.order("sample_small_decimal DESC").load
    else
      types = DbType.all :order => "sample_small_decimal DESC"
    end
    assert_equal(3.14, types[0].sample_small_decimal)
    assert_equal(1.0, types[1].sample_small_decimal)

    if ar_version('4.0')
      types = DbType.order("sample_small_decimal ASC").load
    else
      types = DbType.all :order => "sample_small_decimal ASC"
    end
    assert_equal(1.0, types[0].sample_small_decimal)
    assert_equal(3.14, types[1].sample_small_decimal)
  end

  def test_binary
    types = DbType.first
    assert_equal(TEST_BINARY, types.sample_binary)
  end

  class DualEncoding < ActiveRecord::Base
  end

  def test_quote_binary_column_escapes_it
    DualEncoding.connection.execute(<<-eosql)
      CREATE TABLE dual_encodings (
        id integer PRIMARY KEY AUTOINCREMENT,
        name string,
        data binary
      )
    eosql
    str = "01 \x80"
    str.force_encoding('ASCII-8BIT') if str.respond_to?(:force_encoding)
    binary = DualEncoding.new :name => '12ščťžýáííéúäô', :data => str
    binary.save!
    assert_equal str, binary.data
    binary.reload
    if str.respond_to?(:force_encoding)
      assert_equal '12ščťžýáííéúäô'.force_encoding('UTF-8'), binary.name
      assert_equal "01 \x80".force_encoding('ASCII-8BIT'), binary.data
    else
      assert_equal '12ščťžýáííéúäô', binary.name
      assert_equal "01 \x80", binary.data
    end
  end

end