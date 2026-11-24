package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class R2StorageKeyRegexTest {

  private final Pattern catalogPattern =
      Pattern.compile("(catalog|catalog-processing)/products/[A-Za-z0-9_/\\-]+[.]png");
  private final Pattern avatarPattern = Pattern.compile("avatars/[A-Za-z0-9_/\\-]+[.]png");
  private final Pattern tryonPattern = Pattern.compile("tryon/[A-Za-z0-9_\\-]+/[A-Za-z0-9_\\-]+[.]png");
  private final Pattern chatPattern = Pattern.compile("chat/[A-Za-z0-9_\\-]+/[A-Za-z0-9_\\-]+[.]png");

  @Test
  void testTryOnKeyValid() {
    String key1 = "tryon/TD00000001/550e8400-e29b-41d4-a716-446655440000--person.png";
    String key2 = "tryon/TD00000001/550e8400-e29b-41d4-a716-446655440000--result.png";

    assertTrue(tryonPattern.matcher(key1).matches());
    assertTrue(tryonPattern.matcher(key2).matches());
    assertTrue(chatPattern.matcher("chat/ND00000005/msg-12345678abcd.png").matches());
    assertFalse(chatPattern.matcher("chat/../escape.png").matches());
  }

  @Test
  void testCatalogAndAvatarKeyValid() {
    String catKey = "catalog/products/SP00000001/variant_black-1.png";
    String catProcKey = "catalog-processing/products/SP00000001/cutout.png";
    String avatarKey = "avatars/USR00000001/profile-photo.png";

    assertTrue(catalogPattern.matcher(catKey).matches());
    assertTrue(catalogPattern.matcher(catProcKey).matches());
    assertTrue(avatarPattern.matcher(avatarKey).matches());
  }

  @Test
  void testInvalidKeys() {
    assertFalse(tryonPattern.matcher("tryon/../escape.png").matches());
    assertFalse(tryonPattern.matcher("tryon/TD001/image.jpg").matches());
  }
}
