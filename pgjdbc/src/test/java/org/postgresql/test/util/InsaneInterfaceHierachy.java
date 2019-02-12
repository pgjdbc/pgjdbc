/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

/**
 * An insane interface hierarchy, useful to test the scalability and implementations
 * of interface graph traversals.  A naive recursive traversal of all interfaces
 * and their parent/extended interfaces would take 2 ^ {@link InsaneInterfaceHierachy#DEPTH}
 * operations.
 */
public class InsaneInterfaceHierachy {

  private InsaneInterfaceHierachy() {}

  /**
   * The depth of the interface hierarchy.  We tried to go big, but too many tools
   * could not handle it.  Currently set to {@code 16}.
   * <p>
   * NetBeans 8.0.2 and Maven 3.6.0 do function, but with long periods of 100% CPU, at 24.
   * </p>
   * <p>
   * Not a chance at the full 64.  In an ideal world, all tools would test against
   * this type of insanity, because it reveals likely suboptimal solutions to
   * even the normal, sane cases.
   * </p>
   * <p>
   * To change this value and test higher levels of insanity:
   * </p>
   * <ol>
   * <li>Update the top of this comment.</li>
   * <li>Update this constant.</li>
   * <li>Move the related lines out of the comment below.  This is where tools
   *     will start to fall flat.</li>
   * <li>Update the {@code extends} declaration on {@link InsaneInterface}.</li>
   * </ol>
   */
  public static final int DEPTH = 16;

  public interface TestHierarchy_1_1 {}

  public interface TestHierarchy_1_2 {}

  public interface TestHierarchy_2_1 extends TestHierarchy_1_1, TestHierarchy_1_2 {}

  public interface TestHierarchy_2_2 extends TestHierarchy_1_1, TestHierarchy_1_2 {}

  public interface TestHierarchy_3_1 extends TestHierarchy_2_1, TestHierarchy_2_2 {}

  public interface TestHierarchy_3_2 extends TestHierarchy_2_1, TestHierarchy_2_2 {}

  public interface TestHierarchy_4_1 extends TestHierarchy_3_1, TestHierarchy_3_2 {}

  public interface TestHierarchy_4_2 extends TestHierarchy_3_1, TestHierarchy_3_2 {}

  public interface TestHierarchy_5_1 extends TestHierarchy_4_1, TestHierarchy_4_2 {}

  public interface TestHierarchy_5_2 extends TestHierarchy_4_1, TestHierarchy_4_2 {}

  public interface TestHierarchy_6_1 extends TestHierarchy_5_1, TestHierarchy_5_2 {}

  public interface TestHierarchy_6_2 extends TestHierarchy_5_1, TestHierarchy_5_2 {}

  public interface TestHierarchy_7_1 extends TestHierarchy_6_1, TestHierarchy_6_2 {}

  public interface TestHierarchy_7_2 extends TestHierarchy_6_1, TestHierarchy_6_2 {}

  public interface TestHierarchy_8_1 extends TestHierarchy_7_1, TestHierarchy_7_2 {}

  public interface TestHierarchy_8_2 extends TestHierarchy_7_1, TestHierarchy_7_2 {}

  public interface TestHierarchy_9_1 extends TestHierarchy_8_1, TestHierarchy_8_2 {}

  public interface TestHierarchy_9_2 extends TestHierarchy_8_1, TestHierarchy_8_2 {}

  public interface TestHierarchy_10_1 extends TestHierarchy_9_1, TestHierarchy_9_2 {}

  public interface TestHierarchy_10_2 extends TestHierarchy_9_1, TestHierarchy_9_2 {}

  public interface TestHierarchy_11_1 extends TestHierarchy_10_1, TestHierarchy_10_2 {}

  public interface TestHierarchy_11_2 extends TestHierarchy_10_1, TestHierarchy_10_2 {}

  public interface TestHierarchy_12_1 extends TestHierarchy_11_1, TestHierarchy_11_2 {}

  public interface TestHierarchy_12_2 extends TestHierarchy_11_1, TestHierarchy_11_2 {}

  public interface TestHierarchy_13_1 extends TestHierarchy_12_1, TestHierarchy_12_2 {}

  public interface TestHierarchy_13_2 extends TestHierarchy_12_1, TestHierarchy_12_2 {}

  public interface TestHierarchy_14_1 extends TestHierarchy_13_1, TestHierarchy_13_2 {}

  public interface TestHierarchy_14_2 extends TestHierarchy_13_1, TestHierarchy_13_2 {}

  public interface TestHierarchy_15_1 extends TestHierarchy_14_1, TestHierarchy_14_2 {}

  public interface TestHierarchy_15_2 extends TestHierarchy_14_1, TestHierarchy_14_2 {}

  public interface TestHierarchy_16_1 extends TestHierarchy_15_1, TestHierarchy_15_2 {}

  public interface TestHierarchy_16_2 extends TestHierarchy_15_1, TestHierarchy_15_2 {}

  /*
   * We had gone to 64, but it seems so many other tools do not scale well, such as NetBeans
   * 8.0.2 running CPU 100% and Maven builds never completing.  This is a useful test, but
   * so many other components, that were are not in control of, have issues.
   *
  public interface TestHierarchy_17_1 extends TestHierarchy_16_1, TestHierarchy_16_2 {}

  public interface TestHierarchy_17_2 extends TestHierarchy_16_1, TestHierarchy_16_2 {}

  public interface TestHierarchy_18_1 extends TestHierarchy_17_1, TestHierarchy_17_2 {}

  public interface TestHierarchy_18_2 extends TestHierarchy_17_1, TestHierarchy_17_2 {}

  public interface TestHierarchy_19_1 extends TestHierarchy_18_1, TestHierarchy_18_2 {}

  public interface TestHierarchy_19_2 extends TestHierarchy_18_1, TestHierarchy_18_2 {}

  public interface TestHierarchy_20_1 extends TestHierarchy_19_1, TestHierarchy_19_2 {}

  public interface TestHierarchy_20_2 extends TestHierarchy_19_1, TestHierarchy_19_2 {}

  public interface TestHierarchy_21_1 extends TestHierarchy_20_1, TestHierarchy_20_2 {}

  public interface TestHierarchy_21_2 extends TestHierarchy_20_1, TestHierarchy_20_2 {}

  public interface TestHierarchy_22_1 extends TestHierarchy_21_1, TestHierarchy_21_2 {}

  public interface TestHierarchy_22_2 extends TestHierarchy_21_1, TestHierarchy_21_2 {}

  public interface TestHierarchy_23_1 extends TestHierarchy_22_1, TestHierarchy_22_2 {}

  public interface TestHierarchy_23_2 extends TestHierarchy_22_1, TestHierarchy_22_2 {}

  public interface TestHierarchy_24_1 extends TestHierarchy_23_1, TestHierarchy_23_2 {}

  public interface TestHierarchy_24_2 extends TestHierarchy_23_1, TestHierarchy_23_2 {}

  public interface TestHierarchy_25_1 extends TestHierarchy_24_1, TestHierarchy_24_2 {}

  public interface TestHierarchy_25_2 extends TestHierarchy_24_1, TestHierarchy_24_2 {}

  public interface TestHierarchy_26_1 extends TestHierarchy_25_1, TestHierarchy_25_2 {}

  public interface TestHierarchy_26_2 extends TestHierarchy_25_1, TestHierarchy_25_2 {}

  public interface TestHierarchy_27_1 extends TestHierarchy_26_1, TestHierarchy_26_2 {}

  public interface TestHierarchy_27_2 extends TestHierarchy_26_1, TestHierarchy_26_2 {}

  public interface TestHierarchy_28_1 extends TestHierarchy_27_1, TestHierarchy_27_2 {}

  public interface TestHierarchy_28_2 extends TestHierarchy_27_1, TestHierarchy_27_2 {}

  public interface TestHierarchy_29_1 extends TestHierarchy_28_1, TestHierarchy_28_2 {}

  public interface TestHierarchy_29_2 extends TestHierarchy_28_1, TestHierarchy_28_2 {}

  public interface TestHierarchy_30_1 extends TestHierarchy_29_1, TestHierarchy_29_2 {}

  public interface TestHierarchy_30_2 extends TestHierarchy_29_1, TestHierarchy_29_2 {}

  public interface TestHierarchy_31_1 extends TestHierarchy_30_1, TestHierarchy_30_2 {}

  public interface TestHierarchy_31_2 extends TestHierarchy_30_1, TestHierarchy_30_2 {}

  public interface TestHierarchy_32_1 extends TestHierarchy_31_1, TestHierarchy_31_2 {}

  public interface TestHierarchy_32_2 extends TestHierarchy_31_1, TestHierarchy_31_2 {}

  public interface TestHierarchy_33_1 extends TestHierarchy_32_1, TestHierarchy_32_2 {}

  public interface TestHierarchy_33_2 extends TestHierarchy_32_1, TestHierarchy_32_2 {}

  public interface TestHierarchy_34_1 extends TestHierarchy_33_1, TestHierarchy_33_2 {}

  public interface TestHierarchy_34_2 extends TestHierarchy_33_1, TestHierarchy_33_2 {}

  public interface TestHierarchy_35_1 extends TestHierarchy_34_1, TestHierarchy_34_2 {}

  public interface TestHierarchy_35_2 extends TestHierarchy_34_1, TestHierarchy_34_2 {}

  public interface TestHierarchy_36_1 extends TestHierarchy_35_1, TestHierarchy_35_2 {}

  public interface TestHierarchy_36_2 extends TestHierarchy_35_1, TestHierarchy_35_2 {}

  public interface TestHierarchy_37_1 extends TestHierarchy_36_1, TestHierarchy_36_2 {}

  public interface TestHierarchy_37_2 extends TestHierarchy_36_1, TestHierarchy_36_2 {}

  public interface TestHierarchy_38_1 extends TestHierarchy_37_1, TestHierarchy_37_2 {}

  public interface TestHierarchy_38_2 extends TestHierarchy_37_1, TestHierarchy_37_2 {}

  public interface TestHierarchy_39_1 extends TestHierarchy_38_1, TestHierarchy_38_2 {}

  public interface TestHierarchy_39_2 extends TestHierarchy_38_1, TestHierarchy_38_2 {}

  public interface TestHierarchy_40_1 extends TestHierarchy_39_1, TestHierarchy_39_2 {}

  public interface TestHierarchy_40_2 extends TestHierarchy_39_1, TestHierarchy_39_2 {}

  public interface TestHierarchy_41_1 extends TestHierarchy_40_1, TestHierarchy_40_2 {}

  public interface TestHierarchy_41_2 extends TestHierarchy_40_1, TestHierarchy_40_2 {}

  public interface TestHierarchy_42_1 extends TestHierarchy_41_1, TestHierarchy_41_2 {}

  public interface TestHierarchy_42_2 extends TestHierarchy_41_1, TestHierarchy_41_2 {}

  public interface TestHierarchy_43_1 extends TestHierarchy_42_1, TestHierarchy_42_2 {}

  public interface TestHierarchy_43_2 extends TestHierarchy_42_1, TestHierarchy_42_2 {}

  public interface TestHierarchy_44_1 extends TestHierarchy_43_1, TestHierarchy_43_2 {}

  public interface TestHierarchy_44_2 extends TestHierarchy_43_1, TestHierarchy_43_2 {}

  public interface TestHierarchy_45_1 extends TestHierarchy_44_1, TestHierarchy_44_2 {}

  public interface TestHierarchy_45_2 extends TestHierarchy_44_1, TestHierarchy_44_2 {}

  public interface TestHierarchy_46_1 extends TestHierarchy_45_1, TestHierarchy_45_2 {}

  public interface TestHierarchy_46_2 extends TestHierarchy_45_1, TestHierarchy_45_2 {}

  public interface TestHierarchy_47_1 extends TestHierarchy_46_1, TestHierarchy_46_2 {}

  public interface TestHierarchy_47_2 extends TestHierarchy_46_1, TestHierarchy_46_2 {}

  public interface TestHierarchy_48_1 extends TestHierarchy_47_1, TestHierarchy_47_2 {}

  public interface TestHierarchy_48_2 extends TestHierarchy_47_1, TestHierarchy_47_2 {}

  public interface TestHierarchy_49_1 extends TestHierarchy_48_1, TestHierarchy_48_2 {}

  public interface TestHierarchy_49_2 extends TestHierarchy_48_1, TestHierarchy_48_2 {}

  public interface TestHierarchy_50_1 extends TestHierarchy_49_1, TestHierarchy_49_2 {}

  public interface TestHierarchy_50_2 extends TestHierarchy_49_1, TestHierarchy_49_2 {}

  public interface TestHierarchy_51_1 extends TestHierarchy_50_1, TestHierarchy_50_2 {}

  public interface TestHierarchy_51_2 extends TestHierarchy_50_1, TestHierarchy_50_2 {}

  public interface TestHierarchy_52_1 extends TestHierarchy_51_1, TestHierarchy_51_2 {}

  public interface TestHierarchy_52_2 extends TestHierarchy_51_1, TestHierarchy_51_2 {}

  public interface TestHierarchy_53_1 extends TestHierarchy_52_1, TestHierarchy_52_2 {}

  public interface TestHierarchy_53_2 extends TestHierarchy_52_1, TestHierarchy_52_2 {}

  public interface TestHierarchy_54_1 extends TestHierarchy_53_1, TestHierarchy_53_2 {}

  public interface TestHierarchy_54_2 extends TestHierarchy_53_1, TestHierarchy_53_2 {}

  public interface TestHierarchy_55_1 extends TestHierarchy_54_1, TestHierarchy_54_2 {}

  public interface TestHierarchy_55_2 extends TestHierarchy_54_1, TestHierarchy_54_2 {}

  public interface TestHierarchy_56_1 extends TestHierarchy_55_1, TestHierarchy_55_2 {}

  public interface TestHierarchy_56_2 extends TestHierarchy_55_1, TestHierarchy_55_2 {}

  public interface TestHierarchy_57_1 extends TestHierarchy_56_1, TestHierarchy_56_2 {}

  public interface TestHierarchy_57_2 extends TestHierarchy_56_1, TestHierarchy_56_2 {}

  public interface TestHierarchy_58_1 extends TestHierarchy_57_1, TestHierarchy_57_2 {}

  public interface TestHierarchy_58_2 extends TestHierarchy_57_1, TestHierarchy_57_2 {}

  public interface TestHierarchy_59_1 extends TestHierarchy_58_1, TestHierarchy_58_2 {}

  public interface TestHierarchy_59_2 extends TestHierarchy_58_1, TestHierarchy_58_2 {}

  public interface TestHierarchy_60_1 extends TestHierarchy_59_1, TestHierarchy_59_2 {}

  public interface TestHierarchy_60_2 extends TestHierarchy_59_1, TestHierarchy_59_2 {}

  public interface TestHierarchy_61_1 extends TestHierarchy_60_1, TestHierarchy_60_2 {}

  public interface TestHierarchy_61_2 extends TestHierarchy_60_1, TestHierarchy_60_2 {}

  public interface TestHierarchy_62_1 extends TestHierarchy_61_1, TestHierarchy_61_2 {}

  public interface TestHierarchy_62_2 extends TestHierarchy_61_1, TestHierarchy_61_2 {}

  public interface TestHierarchy_63_1 extends TestHierarchy_62_1, TestHierarchy_62_2 {}

  public interface TestHierarchy_63_2 extends TestHierarchy_62_1, TestHierarchy_62_2 {}

  public interface TestHierarchy_64_1 extends TestHierarchy_63_1, TestHierarchy_63_2 {}

  public interface TestHierarchy_64_2 extends TestHierarchy_63_1, TestHierarchy_63_2 {}
   */
}
